package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Service
public class FtsmWebsiteCrawler {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";
    private static final List<String> CONTENT_SELECTORS =
            List.of("main", "article", "#app", "#content", ".content", ".entry-content", "body");

    private final AppConfig appConfig;

    public FtsmWebsiteCrawler(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public CrawlResult crawl(int maxPages, Consumer<CrawlProgress> progressConsumer) throws Exception {
        AppConfig.CrawlerProperties config = appConfig.getCrawler();
        Exception browserFailure = null;

        if (config.isBrowserEnabled()) {
            try {
                CrawlResult result = crawlWithBrowser(maxPages, progressConsumer);
                if (result.pagesCrawled() > 0) {
                    return result;
                }
            } catch (Exception error) {
                browserFailure = error;
                log.warn("Browser crawler failed, static fallback will be attempted: {}", error.getMessage());
            }
        }

        if (!config.isStaticFallback()) {
            if (browserFailure != null) {
                throw browserFailure;
            }
            throw new IllegalStateException("Browser crawling is disabled and static fallback is not allowed");
        }

        CrawlResult result = crawlStatic(maxPages, progressConsumer);
        if (result.pagesCrawled() == 0 && browserFailure != null) {
            throw new IllegalStateException(
                    "Browser and static crawlers returned no usable pages. Browser error: "
                            + browserFailure.getMessage()
            );
        }
        return result;
    }

    private CrawlResult crawlWithBrowser(
            int maxPages, Consumer<CrawlProgress> progressConsumer) throws Exception {
        Map<String, String> playwrightEnvironment = new HashMap<>();
        if (!appConfig.getCrawler().isBrowserAutoDownload()) {
            playwrightEnvironment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        }
        try (Playwright playwright = Playwright.create(
                new Playwright.CreateOptions().setEnv(playwrightEnvironment))) {
            BrowserLaunch browserLaunch = launchBrowser(playwright);
            try (Browser browser = browserLaunch.browser();
                 BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                         .setViewportSize(1280, 900)
                         .setUserAgent(USER_AGENT)
                         .setLocale("en-US")
                         .setTimezoneId("Asia/Kuala_Lumpur"))) {
                context.route("**/*", route -> {
                    String resourceType = route.request().resourceType();
                    if ("image".equals(resourceType)
                            || "media".equals(resourceType)
                            || "font".equals(resourceType)) {
                        route.abort();
                    } else {
                        route.resume();
                    }
                });
                Page page = context.newPage();
                AtomicBoolean staticFallbackUsed = new AtomicBoolean(false);
                CrawlResult result = crawlQueue(
                        maxPages,
                        browserLaunch.engine(),
                        progressConsumer,
                        url -> {
                            PageData data = extractBrowserPage(page, url);
                            if (data == null && appConfig.getCrawler().isStaticFallback()) {
                                staticFallbackUsed.set(true);
                                return extractStaticPage(url);
                            }
                            return data;
                        }
                );
                if (!staticFallbackUsed.get()) {
                    return result;
                }
                return new CrawlResult(
                        result.outputFile(),
                        result.pagesCrawled(),
                        result.pagesVisited(),
                        result.pagesSkipped(),
                        result.pagesFailed(),
                        result.engine() + "+jsoup-fallback"
                );
            }
        }
    }

    private BrowserLaunch launchBrowser(Playwright playwright) {
        List<String> failures = new ArrayList<>();
        List<String> channels = appConfig.getCrawler().getBrowserChannels();
        if (channels == null || channels.isEmpty()) {
            channels = List.of("msedge", "chrome", "");
        }

        for (String configuredChannel : channels) {
            String channel = configuredChannel == null ? "" : configuredChannel.trim();
            String label = channel.isEmpty() ? "playwright-chromium" : channel;
            try {
                BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                        .setHeadless(appConfig.getCrawler().isHeadless())
                        .setArgs(List.of("--disable-blink-features=AutomationControlled"));
                if (!channel.isEmpty()) {
                    options.setChannel(channel);
                }
                Browser browser = playwright.chromium().launch(options);
                log.info("Crawler browser started with {}", label);
                return new BrowserLaunch(browser, label);
            } catch (Exception error) {
                failures.add(label + ": " + oneLine(error.getMessage()));
            }
        }
        throw new IllegalStateException("No supported browser could be started: " + String.join("; ", failures));
    }

    private PageData extractBrowserPage(Page page, String requestedUrl) {
        try {
            page.navigate(requestedUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(appConfig.getCrawler().getPageTimeoutSeconds() * 1000.0));

            for (int attempt = 0; attempt < 30; attempt++) {
                Number bodyLength = (Number) page.evaluate(
                        "() => document.body ? document.body.innerText.length : 0"
                );
                if (bodyLength.intValue() > 500) {
                    break;
                }
                page.waitForTimeout(500);
            }

            page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
            page.waitForTimeout(1200);
            page.evaluate("() => window.scrollTo(0, 0)");
            page.waitForTimeout(300);

            String finalUrl = normalizeUrl(page.url());
            if (!isAllowedUrl(finalUrl)) {
                throw new IllegalStateException("Page redirected outside the crawler allowlist");
            }

            String rawText = "";
            for (String selector : CONTENT_SELECTORS) {
                Locator locator = page.locator(selector);
                if (locator.count() == 0) {
                    continue;
                }
                String candidate = locator.first().innerText();
                if (candidate != null && candidate.strip().length() > 300) {
                    rawText = candidate;
                    break;
                }
            }

            String content = cleanText(rawText);
            if (content.length() < appConfig.getCrawler().getMinContentChars()) {
                return null;
            }
            List<String> links = extractLinks(Jsoup.parse(page.content(), finalUrl));
            return new PageData(finalUrl, cleanText(page.title()), content, links);
        } catch (Exception error) {
            log.warn("Browser page failed {}: {}", requestedUrl, oneLine(error.getMessage()));
            return null;
        }
    }

    private CrawlResult crawlStatic(
            int maxPages, Consumer<CrawlProgress> progressConsumer) throws Exception {
        return crawlQueue(maxPages, "jsoup", progressConsumer, this::extractStaticPage);
    }

    private PageData extractStaticPage(String requestedUrl) {
        try {
            Document page = fetchStaticDocument(requestedUrl);
            String finalUrl = normalizeUrl(page.location());
            if (!isAllowedUrl(finalUrl)) {
                throw new IllegalStateException("Page redirected outside the crawler allowlist");
            }
            page.select("script, style, noscript, svg, form, iframe").remove();

            String rawText = "";
            for (String selector : CONTENT_SELECTORS) {
                Element element = page.selectFirst(selector);
                if (element == null) {
                    continue;
                }
                String candidate = element.text();
                if (candidate.length() > 200) {
                    rawText = candidate;
                    break;
                }
            }
            String content = cleanText(rawText);
            if (content.length() < Math.min(160, appConfig.getCrawler().getMinContentChars())) {
                return null;
            }
            return new PageData(finalUrl, cleanText(page.title()), content, extractLinks(page));
        } catch (Exception error) {
            log.warn("Static page failed {}: {}", requestedUrl, oneLine(error.getMessage()));
            return null;
        }
    }

    private Document fetchStaticDocument(String requestedUrl) throws Exception {
        try {
            return staticConnection(requestedUrl).get();
        } catch (SSLHandshakeException certificateError) {
            if (!appConfig.getCrawler().isStaticTlsFallback() || !isAllowedUrl(requestedUrl)) {
                throw certificateError;
            }
            log.warn("TLS verification failed for allowlisted crawler URL {}; retrying with scoped fallback",
                    requestedUrl);
            return staticConnection(requestedUrl)
                    .sslSocketFactory(insecureCrawlerSocketFactory())
                    .get();
        }
    }

    private Connection staticConnection(String requestedUrl) {
        return Jsoup.connect(requestedUrl)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(appConfig.getCrawler().getPageTimeoutSeconds() * 1000)
                    .followRedirects(true)
                    .ignoreContentType(false)
                    .maxBodySize(10 * 1024 * 1024);
    }

    private static SSLSocketFactory insecureCrawlerSocketFactory() throws Exception {
        TrustManager[] trustManagers = {
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }
                }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        return context.getSocketFactory();
    }

    private CrawlResult crawlQueue(
            int maxPages,
            String engine,
            Consumer<CrawlProgress> progressConsumer,
            PageExtractor extractor) throws Exception {
        Queue<String> queue = new ArrayDeque<>();
        Set<String> queued = new HashSet<>();
        for (String seed : appConfig.getCrawler().getSeedUrls()) {
            String normalized = normalizeUrl(seed);
            if (!normalized.isBlank() && queued.add(normalized)) {
                queue.add(normalized);
            }
        }

        Set<String> visited = new LinkedHashSet<>();
        Set<String> contentFingerprints = new HashSet<>();
        List<PageData> pages = new ArrayList<>();
        int skipped = 0;
        int failed = 0;
        int maxQueuedLinks = Math.max(maxPages, appConfig.getCrawler().getMaxQueuedLinks());

        while (!queue.isEmpty() && visited.size() < maxPages) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Website crawl was interrupted");
            }
            String url = queue.remove();
            if (!isAllowedUrl(url) || shouldSkipUrl(url) || !visited.add(url)) {
                skipped++;
                continue;
            }

            PageData page = extractor.extract(url);
            if (page == null) {
                failed++;
            } else {
                String fingerprint = sha256(page.content());
                if (contentFingerprints.add(fingerprint)) {
                    pages.add(page);
                } else {
                    skipped++;
                }
                for (String link : page.links()) {
                    if (queue.size() >= maxQueuedLinks) {
                        break;
                    }
                    if (!visited.contains(link) && queued.add(link)) {
                        queue.add(link);
                    }
                }
            }

            progressConsumer.accept(new CrawlProgress(
                    engine, visited.size(), pages.size(), skipped, failed, url
            ));
            int delay = appConfig.getCrawler().getPageDelayMillis();
            if (delay > 0) {
                Thread.sleep(delay);
            }
        }

        if (pages.isEmpty()) {
            throw new IllegalStateException(engine + " crawler returned no usable pages");
        }
        Path output = writeOutput(pages, engine);
        return new CrawlResult(output, pages.size(), visited.size(), skipped, failed, engine);
    }

    private Path writeOutput(List<PageData> pages, String engine) throws Exception {
        Path output = Paths.get(
                appConfig.getQdrant().getDataPath(),
                appConfig.getCrawler().getOutputFilename()
        );
        Path absoluteOutput = output.toAbsolutePath().normalize();
        Files.createDirectories(absoluteOutput.getParent());
        Path temp = absoluteOutput.resolveSibling(absoluteOutput.getFileName() + ".tmp");

        StringBuilder text = new StringBuilder();
        text.append("FTSM UKM Official Website Content\n");
        text.append("Source type: Browser-rendered and static official web content\n");
        text.append("Crawler engine: ").append(engine).append('\n');
        text.append("Crawled at: ").append(Instant.now()).append('\n');
        text.append("Total pages: ").append(pages.size()).append('\n');
        text.append("=".repeat(80)).append("\n\n");

        for (int index = 0; index < pages.size(); index++) {
            PageData page = pages.get(index);
            text.append("[Page ").append(index + 1).append("]\n");
            text.append("URL: ").append(page.url()).append('\n');
            text.append("Title: ").append(page.title()).append("\n\n");
            text.append(page.content()).append('\n');
            text.append("\n").append("-".repeat(60)).append("\n\n");
        }

        Files.writeString(temp, text, StandardCharsets.UTF_8);
        if (Files.size(temp) == 0) {
            Files.deleteIfExists(temp);
            throw new IllegalStateException("Crawler output was empty; existing file was preserved");
        }
        try {
            Files.move(temp, absoluteOutput,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveError) {
            Files.move(temp, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
        }
        return absoluteOutput;
    }

    List<String> extractLinks(Document page) {
        Set<String> links = new LinkedHashSet<>();
        page.select("a[href]").forEach(element -> {
            String link = normalizeUrl(element.absUrl("href"));
            if (!link.isBlank() && isAllowedUrl(link) && !shouldSkipUrl(link)) {
                links.add(link);
            }
        });
        return new ArrayList<>(links);
    }

    boolean isAllowedUrl(String url) {
        String normalized = normalizeUrl(url);
        if (normalized.isBlank()) {
            return false;
        }
        String comparable = normalized + "/";
        for (String configuredPrefix : appConfig.getCrawler().getAllowedUrlPrefixes()) {
            String prefix = normalizeUrl(configuredPrefix);
            if (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (!prefix.isBlank() && comparable.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    boolean shouldSkipUrl(String url) {
        String lower = normalizeUrl(url).toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        String path = query >= 0 ? lower.substring(0, query) : lower;
        for (String extension : appConfig.getCrawler().getSkipExtensions()) {
            if (path.endsWith(extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return lower.contains("/login")
                || lower.contains("/register")
                || lower.contains("wp-admin")
                || lower.contains("wp-login");
    }

    static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI source = URI.create(url.trim());
            String scheme = source.getScheme();
            String host = source.getHost();
            if (scheme == null || host == null || !"https".equalsIgnoreCase(scheme)) {
                return "";
            }
            int port = source.getPort();
            if (port != -1 && port != 443) {
                return "";
            }
            String path = source.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            path = path.replaceAll("/{2,}", "/");
            if (path.length() > 1) {
                path = path.replaceAll("/+$", "");
            }
            URI normalized = new URI(
                    "https",
                    null,
                    host.toLowerCase(Locale.ROOT),
                    -1,
                    path,
                    source.getRawQuery(),
                    null
            );
            return normalized.toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException error) {
            return "";
        }
    }

    static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        List<String> lines = text.replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(line -> line.trim().replaceAll("[\\t ]+", " "))
                .filter(line -> line.length() > 2)
                .toList();
        return String.join("\n", lines)
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte part : digest) {
            result.append(String.format("%02x", part));
        }
        return result.toString();
    }

    private static String oneLine(String value) {
        return value == null ? "unknown error" : value.replaceAll("\\s+", " ").trim();
    }

    @FunctionalInterface
    private interface PageExtractor {
        PageData extract(String url);
    }

    private record PageData(String url, String title, String content, List<String> links) {
    }

    private record BrowserLaunch(Browser browser, String engine) {
    }

    public record CrawlProgress(
            String engine,
            int pagesVisited,
            int pagesCrawled,
            int pagesSkipped,
            int pagesFailed,
            String currentUrl) {
    }

    public record CrawlResult(
            Path outputFile,
            int pagesCrawled,
            int pagesVisited,
            int pagesSkipped,
            int pagesFailed,
            String engine) {
    }
}
