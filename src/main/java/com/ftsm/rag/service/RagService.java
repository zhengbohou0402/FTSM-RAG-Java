package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.utils.QueryPreprocessor;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagService {

    private final AppConfig appConfig;
    private final VectorStoreService vectorStoreService;
    private final VertexAiRankingClient vertexAiRankingClient;
    private final ModelFactory modelFactory;
    private final QueryPreprocessor queryPreprocessor;
    private final SystemPromptService systemPromptService;
    private final TraceLogger traceLogger;

    @Value("classpath:prompts/rag_summarize.txt")
    private Resource ragPromptResource;

    private String ragPromptTemplateText;

    private static final int MAX_SOURCES = 5;
    private static final int MAX_SOURCE_EXCERPT_CHARS = 220;
    private static final int RERANK_TOP_N = 6;

    public static final String NO_ANSWER_MESSAGE = 
            "The available UKM FTSM knowledge base does not contain enough confirmed " +
            "information to answer this question. Please verify through the official " +
            "FTSM or UKM channels.";

    private static final List<String> OFFICIAL_SOURCE_PATTERNS = List.of(
            "ftsm_official_website", "academic_calendar", "semester2_exam_schedule",
            "master_coursemode_timetable", "programmes_and_admissions", "facilities_and_services",
            "industrial_training_and_contacts", "advisors_and_academic_staff", "advisors_expertise_index",
            "ukm_campus_bus_routes_guide"
    );

    private static final List<String> COMMUNITY_SOURCE_PATTERNS = List.of(
            "student_portal", "community"
    );

    private static final Set<String> STOP_WORDS = Set.of(
            "about", "after", "again", "also", "and", "are", "can", "check", "does", "for",
            "from", "give", "how", "information", "into", "list", "me", "need", "please",
            "show", "student", "tell", "the", "this", "to", "what", "when", "where", "which",
            "with"
    );

    public RagService(AppConfig appConfig, VectorStoreService vectorStoreService,
                       VertexAiRankingClient vertexAiRankingClient, ModelFactory modelFactory,
                       QueryPreprocessor queryPreprocessor,
                       SystemPromptService systemPromptService,
                       TraceLogger traceLogger) {
        this.appConfig = appConfig;
        this.vectorStoreService = vectorStoreService;
        this.vertexAiRankingClient = vertexAiRankingClient;
        this.modelFactory = modelFactory;
        this.queryPreprocessor = queryPreprocessor;
        this.systemPromptService = systemPromptService;
        this.traceLogger = traceLogger;
    }

    private synchronized String getRagPromptTemplate() {
        if (ragPromptTemplateText == null) {
            try {
                ragPromptTemplateText = StreamUtils.copyToString(ragPromptResource.getInputStream(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Failed to load RAG summarize prompt", e);
                ragPromptTemplateText = "Use the following context to answer the user question:\nContext: {context}\nQuestion: {input}";
            }
        }
        return ragPromptTemplateText;
    }

    private String getDocSourceName(Document doc) {
        Map<String, String> metadata = doc.metadata().asMap();
        String filePath = metadata.getOrDefault("file_path", metadata.getOrDefault("source", ""));
        String fileName = !filePath.isEmpty() ? Paths.get(filePath).getFileName().toString() : "";
        String val = metadata.getOrDefault("filename", metadata.getOrDefault("title", fileName));
        return val != null ? val.toLowerCase() : "unknown";
    }

    private int getSourcePriority(Document doc) {
        Map<String, String> metadata = doc.metadata().asMap();
        String rawPriority = metadata.get("source_priority");
        if (rawPriority != null && !rawPriority.isEmpty()) {
            try {
                return Integer.parseInt(rawPriority);
            } catch (NumberFormatException ignored) {}
        }

        String sourceType = metadata.getOrDefault("source_type", "").toLowerCase();
        if ("official".equals(sourceType)) return 1;
        if ("scraped_website".equals(sourceType)) return 2;
        if ("community_guide".equals(sourceType)) return 3;
        if ("generated_summary".equals(sourceType)) return 4;

        String name = getDocSourceName(doc);
        if (name.contains("ftsm_official_website")) return 2;
        if (OFFICIAL_SOURCE_PATTERNS.stream().anyMatch(name::contains)) return 1;
        if (COMMUNITY_SOURCE_PATTERNS.stream().anyMatch(name::contains)) return 3;
        return 2;
    }

    private List<Document> applySourceWeight(List<Document> docs) {
        List<Document> sorted = new ArrayList<>(docs);
        sorted.sort((d1, d2) -> {
            int idx1 = docs.indexOf(d1);
            int idx2 = docs.indexOf(d2);
            double score1 = idx1 + getWeightAdjustment(d1);
            double score2 = idx2 + getWeightAdjustment(d2);
            return Double.compare(score1, score2);
        });
        return sorted;
    }

    private double getWeightAdjustment(Document doc) {
        int priority = getSourcePriority(doc);
        if (priority == 1) return -0.25;
        if (priority >= 3) return 0.15;
        return 0.0;
    }

    private Map<String, Set<String>> getQueryIdentifiers(String query) {
        String upper = query.toUpperCase();
        Map<String, Set<String>> ids = new HashMap<>();

        // Course codes e.g. FT1023
        Set<String> courses = new HashSet<>();
        Matcher m1 = Pattern.compile("\\b[A-Z]{2}\\d{4}\\b").matcher(upper);
        while (m1.find()) courses.add(m1.group());
        ids.put("course_codes", courses);

        // BK rooms e.g. BK 1
        Set<String> rooms = new HashSet<>();
        Matcher m2 = Pattern.compile("\\bBK\\s*\\d+\\b").matcher(upper);
        while (m2.find()) rooms.add(m2.group().replace(" ", ""));
        ids.put("rooms", rooms);

        // Blocks e.g. BLOCK G
        Set<String> blocks = new HashSet<>();
        Matcher m3 = Pattern.compile("\\bBLOCK\\s+[A-H]\\b").matcher(upper);
        while (m3.find()) blocks.add(m3.group());
        ids.put("blocks", blocks);

        // Years e.g. 2024 or 2024/2025
        Set<String> years = new HashSet<>();
        Matcher m4 = Pattern.compile("\\b20\\d{2}(?:\\s*/\\s*20\\d{2})?\\b").matcher(query);
        while (m4.find()) years.add(m4.group());
        ids.put("years", years);

        // Map terms
        Set<String> maps = new HashSet<>();
        String lower = query.toLowerCase();
        for (String term : List.of("map", "地图", "room", "rooms", "facility", "facilities", "lecture room", "tutorial")) {
            if (lower.contains(term)) maps.add(term);
        }
        ids.put("map_terms", maps);

        // Calendar terms
        Set<String> calendars = new HashSet<>();
        for (String term : List.of("calendar", "kalendar", "校历", "academic", "semester", "sem")) {
            if (lower.contains(term)) calendars.add(term);
        }
        ids.put("calendar_terms", calendars);

        // Registration terms
        Set<String> registrations = new HashSet<>();
        for (String term : List.of("joinukm", "registration", "register", "renewal", "emgs", "visa", "体检", "注册", "续签")) {
            if (lower.contains(term)) registrations.add(term);
        }
        ids.put("registration_terms", registrations);

        return ids;
    }

    private Set<String> getPhraseTerms(String query) {
        Set<String> phrases = new HashSet<>();
        
        Matcher m1 = Pattern.compile("[A-Za-z][A-Za-z0-9&/() -]{4,}").matcher(query);
        while (m1.find()) {
            String phrase = m1.group();
            String cleaned = String.join(" ", phrase.toLowerCase().split("\\s+")).trim();
            if (!cleaned.isEmpty() && !STOP_WORDS.contains(cleaned)) {
                phrases.add(cleaned);
                
                // Extract words inside phrase
                List<String> words = new ArrayList<>();
                Matcher mWords = Pattern.compile("[a-zA-Z0-9]+").matcher(cleaned);
                while (mWords.find()) {
                    String w = mWords.group();
                    if (w.length() > 2 && !STOP_WORDS.contains(w)) {
                        words.add(w);
                    }
                }
                phrases.addAll(words);
                
                // N-grams (2-grams and 3-grams)
                for (int i = 0; i < words.size() - 1; i++) {
                    phrases.add(words.get(i) + " " + words.get(i + 1));
                }
                for (int i = 0; i < words.size() - 2; i++) {
                    phrases.add(words.get(i) + " " + words.get(i + 1) + " " + words.get(i + 2));
                }
            }
        }
        
        Matcher m2 = Pattern.compile("[\\u4e00-\\u9fff]{2,}").matcher(query);
        while (m2.find()) {
            phrases.add(m2.group());
        }
        
        return phrases;
    }

    private double getQueryBoost(String query, Document doc) {
        String text = doc.text().toLowerCase();
        String compactText = text.replace(" ", "");
        String name = getDocSourceName(doc);
        Map<String, Set<String>> ids = getQueryIdentifiers(query);
        double boost = 0.0;

        for (String code : ids.get("course_codes")) {
            if (text.contains(code.toLowerCase())) boost += 5.0;
        }
        for (String room : ids.get("rooms")) {
            if (compactText.contains(room.toLowerCase())) boost += 3.0;
        }
        for (String block : ids.get("blocks")) {
            if (text.contains(block.toLowerCase())) boost += 2.0;
        }
        for (String year : ids.get("years")) {
            if (text.replace(" ", "").contains(year.replace(" ", ""))) boost += 1.25;
        }

        for (String phrase : getPhraseTerms(query)) {
            if (phrase.length() >= 5 && text.contains(phrase.toLowerCase())) {
                boost += phrase.contains(" ") ? 4.0 : 0.75;
            }
        }

        if (!ids.get("map_terms").isEmpty()) {
            if (List.of("faculty map", "ftsm map", "学院地图", "rooms & facilities", "faculty_map")
                    .stream().anyMatch(m -> text.contains(m) || name.contains(m))) {
                boost += 3.0;
            }
        }

        if (!ids.get("calendar_terms").isEmpty()) {
            if (List.of("academic calendar", "kalendar akademik", "校历", "academic_calendar")
                    .stream().anyMatch(m -> text.contains(m) || name.contains(m))) {
                boost += 3.0;
            }
        }

        if (!ids.get("registration_terms").isEmpty()) {
            if (List.of("joinukm", "registration", "renewal", "emgs", "visa", "体检", "注册", "续签")
                    .stream().anyMatch(m -> text.contains(m) || name.contains(m))) {
                boost += 3.0;
            }
        }

        if ((name.contains("coursework_timetable") || name.contains("timetable")) &&
                (!ids.get("course_codes").isEmpty() || !ids.get("rooms").isEmpty())) {
            boost += 1.0;
        }

        if (name.contains("student_portal_ftsm_faculty_map_rooms") &&
                (!ids.get("map_terms").isEmpty() || !ids.get("rooms").isEmpty() || !ids.get("blocks").isEmpty())) {
            boost += 1.0;
        }

        return boost;
    }

    private List<Document> applyQueryBoost(String query, List<Document> docs) {
        List<Document> sorted = new ArrayList<>(docs);
        sorted.sort((d1, d2) -> {
            int idx1 = docs.indexOf(d1);
            int idx2 = docs.indexOf(d2);
            double score1 = idx1 - getQueryBoost(query, d1);
            double score2 = idx2 - getQueryBoost(query, d2);
            return Double.compare(score1, score2);
        });
        return sorted;
    }

    private Set<String> getQueryTerms(String query) {
        Set<String> terms = new HashSet<>();
        Matcher m = Pattern.compile("[a-zA-Z0-9]+").matcher(query);
        while (m.find()) {
            String token = m.group().toLowerCase();
            if (token.length() > 2 && !STOP_WORDS.contains(token)) {
                terms.add(token);
            }
        }
        
        Matcher mChinese = Pattern.compile("[\\u4e00-\\u9fff]{2,}").matcher(query);
        while (mChinese.find()) {
            String phrase = mChinese.group();
            if (phrase.length() <= 4) {
                terms.add(phrase);
            } else {
                for (int i = 0; i < phrase.length() - 1; i++) {
                    terms.add(phrase.substring(i, i + 2));
                }
            }
        }
        return terms;
    }

    private boolean hasRetrievalSignal(String query, List<Document> docs) {
        if (docs.isEmpty()) return false;
        Set<String> terms = getQueryTerms(query);
        if (terms.isEmpty()) return true;

        StringBuilder topTextBuilder = new StringBuilder();
        for (int i = 0; i < Math.min(3, docs.size()); i++) {
            topTextBuilder.append(docs.get(i).text().toLowerCase()).append("\n");
        }
        String topText = topTextBuilder.toString();

        long overlap = terms.stream().filter(term -> topText.contains(term.toLowerCase())).count();
        long requiredOverlap = terms.size() <= 2 ? 1 : 2;
        if (overlap >= requiredOverlap) return true;

        return terms.size() <= 2 && getSourcePriority(docs.get(0)) <= 2;
    }

    private Mono<String> generateHypotheticalDocument(String query) {
        return Mono.fromCallable(() -> {
            String prompt = "Please write a hypothetical, detailed, and factual answer to the following question. " +
                            "Do not include apologies or preambles, just output the facts as if you were an expert.\n\nQuestion: " + query;
            AiMessage response = modelFactory.getChatModel().generate(
                    UserMessage.from(prompt)).content();
            return response != null ? response.text() : "";
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(error -> {
            log.warn("HyDE generation failed: {}", error.getMessage());
            return Mono.just("");
        });
    }

    private Mono<List<Document>> retrieveDocs(String query) {
        long startTime = System.currentTimeMillis();
        return generateHypotheticalDocument(query).flatMap(hypotheticalDoc -> {
            traceLogger.logStage("query_preprocessing", System.currentTimeMillis() - startTime, "query=" + query, "hyde_len=" + (hypotheticalDoc != null ? hypotheticalDoc.length() : 0));
            long retStart = System.currentTimeMillis();
            
            List<String> queries = queryPreprocessor.process(query);
            List<String> allQueries = new ArrayList<>(queries);
            if (hypotheticalDoc != null && !hypotheticalDoc.isBlank()) {
                allQueries.add(hypotheticalDoc);
            }
            int hybridSearchLimit = appConfig.getQdrant().getHybridSearchLimit();
            return Flux.fromIterable(allQueries)
                    .flatMap(singleQuery -> Mono.fromCallable(
                                    () -> vectorStoreService.search(singleQuery, hybridSearchLimit))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(error -> {
                                log.warn("Retrieval failed for query expansion '{}': {}",
                                        singleQuery, error.getMessage());
                                return Mono.just(Collections.<Document>emptyList());
                            }), Math.min(Math.max(allQueries.size(), 1), 4))
                .collectList()
                .map(listsOfDocs -> {
                    List<ScoredDocument> scored = crossQueryRrf(listsOfDocs);
                    List<Document> hybridRanked = scored.stream().map(ScoredDocument::doc).collect(Collectors.toList());
                    
                    int initialCount = hybridRanked.size();
                    hybridRanked = vectorStoreService.mergeChildHitsToParents(hybridRanked);
                    int mergedCount = hybridRanked.size();
                    
                    traceLogger.logStage("retrieval", System.currentTimeMillis() - retStart, 
                            "queries=" + allQueries.size(), 
                            "fusion_strategy=cross_query_rrf",
                            "retrieved=" + initialCount,
                            "merged=" + mergedCount);
                    return hybridRanked;
                })
                .flatMap(hybridRanked -> {
                    if (hybridRanked.isEmpty()) {
                        return Mono.just(hybridRanked);
                    }
                    long rerankStart = System.currentTimeMillis();
                    List<String> docTexts = hybridRanked.stream().map(Document::text).collect(Collectors.toList());
                    
                    return vertexAiRankingClient.rerank(query, docTexts, RERANK_TOP_N)
                            .map(sortedIndices -> {
                                List<Document> reranked = new ArrayList<>();
                                for (int idx : sortedIndices) {
                                    if (idx >= 0 && idx < hybridRanked.size()) {
                                        reranked.add(hybridRanked.get(idx));
                                    }
                                }
                                
                                // If rerank fails or is empty, use first N
                                if (reranked.isEmpty()) {
                                    int limit = Math.min(RERANK_TOP_N, hybridRanked.size());
                                    reranked.addAll(hybridRanked.subList(0, limit));
                                }

                                List<Document> boosted = applyQueryBoost(query, reranked);
                                List<Document> finalDocs = applySourceWeight(boosted);
                                traceLogger.logStage("reranking", System.currentTimeMillis() - rerankStart, "input=" + hybridRanked.size(), "output=" + finalDocs.size());
                                return finalDocs;
                            });
                });
        });
    }

    private List<ScoredDocument> crossQueryRrf(List<List<Document>> queryResults) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();
        for (List<Document> docList : queryResults) {
            for (int i = 0; i < docList.size(); i++) {
                Document doc = docList.get(i);
                String key = documentKey(doc);
                docMap.putIfAbsent(key, doc);
                rrfScores.put(key, rrfScores.getOrDefault(key, 0.0) + 1.0 / (60.0 + i + 1));
            }
        }
        return rrfScores.entrySet().stream()
                .map(e -> new ScoredDocument(docMap.get(e.getKey()), e.getValue()))
                .sorted((a, b) -> Double.compare(b.rrfScore(), a.rrfScore()))
                .collect(Collectors.toList());
    }

    private String documentKey(Document document) {
        String chunkId = document.metadata().getString("chunk_id");
        if (chunkId != null && !chunkId.isBlank()) {
            return chunkId;
        }
        return document.text().substring(0, Math.min(document.text().length(), 100));
    }

    private String buildContext(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            sb.append("[Reference ").append(i + 1).append("] Content: ")
                    .append(doc.text().replace('\n', ' '))
                    .append(" | Metadata: ").append(doc.metadata().asMap())
                    .append("\n");
        }
        return sb.toString();
    }

    private String getSourceTrustLabel(Document doc) {
        Map<String, String> metadata = doc.metadata().asMap();
        String label = metadata.get("source_trust_label");
        if (label != null && !label.trim().isEmpty()) {
            return label.trim();
        }

        String sourceType = metadata.getOrDefault("source_type", "").toLowerCase();
        if ("official".equals(sourceType)) return "Official material";
        if ("scraped_website".equals(sourceType)) return "Scraped official website";
        if ("community_guide".equals(sourceType)) return "Student guide";
        if ("generated_summary".equals(sourceType)) return "Generated summary";

        String name = getDocSourceName(doc);
        if (name.contains("ftsm_official_website")) return "Scraped official website";
        if (name.contains("student_portal")) return "Student guide";
        if (name.contains("index")) return "Generated summary";
        return "Official material";
    }

    public String formatSourceReliability(List<Document> docs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        
        for (Document doc : docs) {
            Map<String, String> metadata = doc.metadata().asMap();
            String docId = metadata.getOrDefault("doc_id", getDocSourceName(doc));
            String chunkIndex = metadata.getOrDefault("chunk_index", "");
            String key = docId + ":" + chunkIndex;
            
            if (seen.contains(key)) continue;
            seen.add(key);

            String label = getSourceTrustLabel(doc);
            counts.put(label, counts.getOrDefault(label, 0) + 1);
            if (seen.size() >= MAX_SOURCES) break;
        }

        if (counts.isEmpty()) return "";
        
        List<String> parts = counts.entrySet().stream()
                .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.toList());

        return "Source reliability: " + String.join("; ", parts) + ".";
    }

    private String getSourceExcerpt(String text) {
        List<String> parts = new ArrayList<>();
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            // Skip dividers
            if (s.length() >= 8 && s.replaceAll("[=\\-_*#]", "").isEmpty()) continue;
            parts.add(s);
        }
        String excerpt = String.join(" ", parts);
        if (excerpt.length() > MAX_SOURCE_EXCERPT_CHARS) {
            excerpt = excerpt.substring(0, MAX_SOURCE_EXCERPT_CHARS).trim() + "...";
        }
        return excerpt;
    }

    public String formatSources(List<Document> docs) {
        List<String> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        for (Document doc : docs) {
            Map<String, String> metadata = doc.metadata().asMap();
            String docId = metadata.getOrDefault("doc_id", getDocSourceName(doc));
            String chunkIndex = metadata.getOrDefault("chunk_index", "");
            String key = docId + ":" + chunkIndex;
            
            if (seen.contains(key)) continue;
            seen.add(key);

            String name = metadata.getOrDefault("filename", getDocSourceName(doc));
            String trustLabel = getSourceTrustLabel(doc);
            String chunkLabel = !chunkIndex.isEmpty() ? ", chunk " + chunkIndex : "";
            String excerpt = getSourceExcerpt(doc.text());
            
            lines.add("- [" + (lines.size() + 1) + "] " + name + " [" + trustLabel + "]" + chunkLabel + ": " + excerpt);
            if (lines.size() >= MAX_SOURCES) break;
        }
        return String.join("\n", lines);
    }

    private Mono<String> compressContext(String query, String rawContext) {
        long startTime = System.currentTimeMillis();
        return Mono.fromCallable(() -> {
            String prompt = "You are an expert context compressor. Your task is to extract ONLY the facts, sentences, and data points from the following context that are directly relevant to answering the user's question. Ignore all irrelevant information, formatting, and noise. If nothing is relevant, output an empty string.\n\n" +
                            "Question: " + query + "\n\n" +
                            "Raw Context:\n" + rawContext;
            dev.langchain4j.data.message.AiMessage response = modelFactory.getChatModel().generate(
                    dev.langchain4j.data.message.UserMessage.from(prompt)).content();
            String compressed = response != null ? response.text() : rawContext;
            
            int estimatedTokens = compressed.length() / 4;
            if (estimatedTokens > appConfig.getMaxContextTokens()) {
                int maxChars = appConfig.getMaxContextTokens() * 4;
                if (compressed.length() > maxChars) {
                    compressed = compressed.substring(0, maxChars);
                }
            }
            
            traceLogger.logStage("compression", System.currentTimeMillis() - startTime, "raw_len=" + rawContext.length(), "compressed_len=" + compressed.length());
            return compressed;
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(error -> {
            log.warn("Context compression failed: {}", error.getMessage());
            String fallback = rawContext;
            int maxChars = appConfig.getMaxContextTokens() * 4;
            if (fallback.length() > maxChars) {
                fallback = fallback.substring(0, maxChars);
            }
            traceLogger.logStage("compression", System.currentTimeMillis() - startTime, "raw_len=" + rawContext.length(), "fallback_len=" + fallback.length());
            return Mono.just(fallback);
        });
    }

    public Mono<PreparedRagAnswer> prepareAnswer(String query) {
        return retrieveDocs(query)
                .flatMap(contextDocs -> {
                    if (!hasRetrievalSignal(query, contextDocs)) {
                        return Mono.just(new PreparedRagAnswer(query, null, "", "", false));
                    }
                    String rawContext = buildContext(contextDocs);
                    return compressContext(query, rawContext).map(compressedContext -> {
                        String promptText = "## Assistant Persona\n"
                                + systemPromptService.getPrompt()
                                + "\n\n## Mandatory Retrieval Instructions\n"
                                + getRagPromptTemplate()
                                .replace("{input}", query)
                                .replace("{context}", compressedContext);
                        return new PreparedRagAnswer(
                                query,
                                promptText,
                                formatSourceReliability(contextDocs),
                                formatSources(contextDocs),
                                true);
                    });
                });
    }

    public Flux<String> streamAnswer(String query) {
        String traceId = TraceLogger.startTrace();
        long startTime = System.currentTimeMillis();
        return prepareAnswer(query).flatMapMany(prepared -> {
            if (!prepared.hasRetrievalSignal()) {
                TraceLogger.clearTrace();
                return Flux.just(NO_ANSWER_MESSAGE);
            }
            Flux<String> generated = streamModel(prepared.prompt())
                    .doOnComplete(() -> {
                        traceLogger.logStage("generation", System.currentTimeMillis() - startTime, "prompt_len=" + prepared.prompt().length(), "complete");
                        TraceLogger.clearTrace();
                    })
                    .doOnError(e -> {
                        TraceLogger.clearTrace();
                    });
            String suffix = sourceSuffix(prepared.reliability(), prepared.sources());
            return suffix.isEmpty() ? generated : Flux.concat(generated, Flux.just(suffix));
        });
    }

    public Mono<String> ragSummarize(String query) {
        return streamAnswer(query)
                .collectList()
                .map(parts -> String.join("", parts).trim());
    }

    private Flux<String> streamModel(String prompt) {
        return Flux.<String>create(sink -> {
            try {
                modelFactory.getStreamingChatModel().generate(
                        prompt,
                        new StreamingResponseHandler<AiMessage>() {
                            @Override
                            public void onNext(String token) {
                                sink.next(token);
                            }

                            @Override
                            public void onComplete(
                                    dev.langchain4j.model.output.Response<AiMessage> response) {
                                sink.complete();
                            }

                            @Override
                            public void onError(Throwable error) {
                                sink.error(error);
                            }
                        });
            } catch (Exception error) {
                sink.error(error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String sourceSuffix(String reliability, String sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        String reliabilityBlock = reliability == null || reliability.isEmpty()
                ? ""
                : "\n\n" + reliability;
        return reliabilityBlock + "\n\nSources:\n" + sources;
    }

    public record PreparedRagAnswer(
            String query,
            String prompt,
            String reliability,
            String sources,
            boolean hasRetrievalSignal) {
    }

    public record ScoredDocument(Document doc, double rrfScore) {}
}
