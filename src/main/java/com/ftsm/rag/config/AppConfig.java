package com.ftsm.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private String projectRoot = "";
    private String llmProvider = "vertexai";
    private VertexAiProperties vertexai = new VertexAiProperties();
    private DashScopeProperties dashscope = new DashScopeProperties();
    private QdrantProperties qdrant = new QdrantProperties();
    private CacheProperties cache = new CacheProperties();
    private ConversationProperties conversation = new ConversationProperties();
    private CrawlerProperties crawler = new CrawlerProperties();

    @Data
    public static class VertexAiProperties {
        private String projectId = "";
        private String location = "us-central1";
        private String modelName = "gemini-1.5-flash";
        private String embeddingModel = "gemini-embedding-001";
        private String credentialsPath = "";
        private String rankingModel = "semantic-ranker-512@latest";
    }

    @Data
    public static class DashScopeProperties {
        private String apiKey = "";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String chatModel = "qwen-plus";
        private String embeddingModel = "text-embedding-v1";
    }


    @Data
    public static class QdrantProperties {
        private String host = "localhost";
        private int port = 6334;
        private boolean useTls = false;
        private String apiKey = "";
        private String collectionName = "ftsm_rag_agent";
        private String persistDirectory = "qdrant_db_ftsm";
        private String sparseModel = "Qdrant/bm25";
        private int k = 6;
        private int hybridSearchLimit = 20;
        private long hybridSearchTimeoutMs = 15_000;
        private int chunkSize = 800;
        private int chunkOverlap = 120;
        private String dataPath = "data/ukm_ftsm";
        private boolean autoStartLocal = true;
        private String localExecutable = "qdrant_local/qdrant.exe";
        private String localStoragePath = "qdrant_local/storage";
        private String lexicalIndexPath = "lucene_local";
        private List<String> allowKnowledgeFileTypes = List.of("txt", "pdf", "png", "jpg", "jpeg", "webp", "gif");
    }

    @Data
    public static class CacheProperties {
        private double threshold = 0.92;
        private int ttlDays = 7;
        private int maxEntries = 500;
    }

    @Data
    public static class ConversationProperties {
        private int maxConversations = 200;
        private int maxMessages = 40;
        private int maxHistoryTurns = 5;
    }

    @Data
    public static class CrawlerProperties {
        private boolean enabled = false;
        private int intervalHours = 168;
        private int maxPages = 80;
        private boolean browserEnabled = true;
        private boolean browserAutoDownload = false;
        private boolean staticFallback = true;
        private boolean staticTlsFallback = true;
        private boolean headless = true;
        private int pageTimeoutSeconds = 40;
        private int pageDelayMillis = 800;
        private int minContentChars = 200;
        private int maxQueuedLinks = 2000;
        private String outputFilename = "ftsm_official_website.txt";
        private List<String> browserChannels = List.of("msedge", "chrome", "");
        private List<String> allowedUrlPrefixes = List.of(
                "https://www.ftsm.ukm.my/",
                "https://ftsm.ukm.my/",
                "https://www.ukm.my/portalukm/",
                "https://www.ukm.my/studyukm/",
                "https://www.ukm.my/akademik/",
                "https://www.ukm.my/hepukm/",
                "https://www.ukm.my/ukmshape/",
                "https://ftsm.pages.dev/"
        );
        private List<String> seedUrls = List.of(
                "https://www.ftsm.ukm.my/v6",
                "https://www.ftsm.ukm.my/v6/background",
                "https://www.ftsm.ukm.my/v6/mission-faculty",
                "https://www.ftsm.ukm.my/v6/quality-statement",
                "https://www.ftsm.ukm.my/v6/chart",
                "https://www.ftsm.ukm.my/v6/faculty-map",
                "https://www.ftsm.ukm.my/v6/faculty-management",
                "https://www.ftsm.ukm.my/v6/why-choose-us",
                "https://www.ftsm.ukm.my/v6/undergraduate",
                "https://www.ftsm.ukm.my/v6/master-program",
                "https://www.ftsm.ukm.my/v6/doctoral-programme",
                "https://www.ftsm.ukm.my/v6/entrepreneurship-programme",
                "https://www.ftsm.ukm.my/v6/staff-academic",
                "https://www.ftsm.ukm.my/v6/staff-admin",
                "https://www.ftsm.ukm.my/v6/staff-ictsupport",
                "https://www.ftsm.ukm.my/v6/adjunct-professor",
                "https://www.ftsm.ukm.my/v6/emeritus-professor",
                "https://www.ftsm.ukm.my/v6/honorary-professor",
                "https://www.ftsm.ukm.my/v6/advisory-board",
                "https://www.ftsm.ukm.my/v6/external-examiner",
                "https://www.ftsm.ukm.my/v6/expertise",
                "https://www.ftsm.ukm.my/v6/research-center",
                "https://www.ftsm.ukm.my/v6/research-university",
                "https://www.ftsm.ukm.my/v6/research-conference",
                "https://www.ftsm.ukm.my/v6/research-guidelineform",
                "https://www.ftsm.ukm.my/v6/publication",
                "https://www.ftsm.ukm.my/v6/technical-report",
                "https://www.ftsm.ukm.my/v6/editing-book",
                "https://www.ftsm.ukm.my/v6/student-affair",
                "https://www.ftsm.ukm.my/v6/industrial-training",
                "https://www.ftsm.ukm.my/v6/fyp",
                "https://www.ftsm.ukm.my/v6/mobility-exchange",
                "https://www.ftsm.ukm.my/v6/hejim",
                "https://www.ftsm.ukm.my/v6/unit-postgraduate",
                "https://www.ftsm.ukm.my/v6/unit-undergraduate",
                "https://www.ftsm.ukm.my/v6/unit-cait",
                "https://www.ftsm.ukm.my/v6/unit-cyber",
                "https://www.ftsm.ukm.my/v6/unit-softam",
                "https://www.ftsm.ukm.my/v6/facility",
                "https://www.ftsm.ukm.my/v6/download",
                "https://www.ftsm.ukm.my/v6/news-event",
                "https://www.ftsm.ukm.my/v6/alumni",
                "https://www.ftsm.ukm.my/v6/sustainability",
                "https://www.ftsm.ukm.my/v6/agreement",
                "https://www.ftsm.ukm.my/v6/online-survey",
                "https://www.ftsm.ukm.my/aksesV2",
                "https://www.ftsm.ukm.my/ethesis",
                "https://www.ftsm.ukm.my/alumniftsm",
                "https://www.ftsm.ukm.my/ftsmgallery",
                "https://ftsm.ukm.my/richsoftam",
                "https://www.ukm.my/portalukm/contact-us/",
                "https://www.ukm.my/portalukm/undergraduate/",
                "https://www.ukm.my/portalukm/undergraduate-requirements/",
                "https://www.ukm.my/studyukm/",
                "https://www.ukm.my/studyukm/postgraduate/",
                "https://www.ukm.my/akademik/kalendar/",
                "https://www.ukm.my/akademik/staff-department/student-admission-unit/",
                "https://www.ukm.my/hepukm/en/",
                "https://www.ukm.my/ukmshape/ukmshape-learning-fees/",
                "https://www.ukm.my/ukmshape/finance-ukmshape/",
                "https://www.ukm.my/ukmshape/frequently-asked-questions-faq/",
                "https://www.ukm.my/ukmshape/renewal--application/",
                "https://ftsm.pages.dev/"
        );
        private List<String> skipExtensions = List.of(
                ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".zip", ".rar",
                ".jpg", ".jpeg", ".png", ".gif", ".webp"
        );
    }
}
