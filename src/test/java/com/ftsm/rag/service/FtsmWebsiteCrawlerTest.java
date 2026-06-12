package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FtsmWebsiteCrawlerTest {

    private FtsmWebsiteCrawler crawler;

    @BeforeEach
    void setUp() {
        AppConfig config = new AppConfig();
        crawler = new FtsmWebsiteCrawler(config);
    }

    @Test
    void crawlerOnlyAcceptsConfiguredHttpsPrefixes() {
        assertTrue(crawler.isAllowedUrl("https://www.ftsm.ukm.my/v6"));
        assertTrue(crawler.isAllowedUrl("https://www.ukm.my/akademik/kalendar/"));
        assertTrue(crawler.isAllowedUrl("https://ftsm.pages.dev/guide"));
        assertFalse(crawler.isAllowedUrl("http://www.ftsm.ukm.my/v6"));
        assertFalse(crawler.isAllowedUrl("https://ukm.my.attacker.example/page"));
        assertFalse(crawler.isAllowedUrl("https://www.ukm.my/other-unapproved-area"));
        assertFalse(crawler.isAllowedUrl("file:///etc/passwd"));
    }

    @Test
    void normalizesFragmentsAndRejectsUnexpectedPorts() {
        assertEquals(
                "https://www.ftsm.ukm.my/v6/background?a=1",
                FtsmWebsiteCrawler.normalizeUrl(
                        "https://WWW.FTSM.UKM.MY:443//v6/background/?a=1#staff"
                )
        );
        assertEquals("", FtsmWebsiteCrawler.normalizeUrl("https://www.ftsm.ukm.my:8443/v6"));
    }

    @Test
    void filtersDownloadsAndDiscoversUniqueRelativeLinks() {
        String html = """
                <html><body>
                  <a href="/v6/background#top">Background</a>
                  <a href="/v6/background">Duplicate</a>
                  <a href="/v6/report.pdf">PDF</a>
                  <a href="https://example.com/outside">Outside</a>
                  <a href="https://www.ukm.my/akademik/kalendar/">Calendar</a>
                </body></html>
                """;

        List<String> links = crawler.extractLinks(
                Jsoup.parse(html, "https://www.ftsm.ukm.my/v6")
        );

        assertEquals(List.of(
                "https://www.ftsm.ukm.my/v6/background",
                "https://www.ukm.my/akademik/kalendar"
        ), links);
        assertTrue(crawler.shouldSkipUrl("https://www.ftsm.ukm.my/v6/report.PDF?download=1"));
        assertTrue(crawler.shouldSkipUrl("https://www.ftsm.ukm.my/wp-login.php"));
        assertFalse(crawler.shouldSkipUrl("https://www.ftsm.ukm.my/v6/staff-academic"));
    }

    @Test
    void cleansBrowserTextWithoutFlatteningSections() {
        assertEquals(
                "Faculty Overview\nResearch and teaching",
                FtsmWebsiteCrawler.cleanText(
                        "  Faculty   Overview \r\n\r\n x \n Research\tand teaching  "
                )
        );
    }
}
