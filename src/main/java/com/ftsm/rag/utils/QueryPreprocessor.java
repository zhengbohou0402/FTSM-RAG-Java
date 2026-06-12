package com.ftsm.rag.utils;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class QueryPreprocessor {

    private final int maxExpansions = 3;

    private static final List<Pattern> NAVIGATION_PATTERNS = List.of(
            Pattern.compile("\\bwhere\\s+can\\s+i\\s+(find|view|check|see|get|look\\s+for)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwhere\\s+to\\s+(find|view|check|see|get|look\\s+for)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bhow\\s+can\\s+i\\s+(find|view|check|see|get|look\\s+for)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bhow\\s+do\\s+i\\s+(find|view|check|see|get|look\\s+for)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcan\\s+you\\s+(tell|show|give)\\s+me\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgive\\s+me\\s+(the\\s+)?(information|details|summary)\\s+(about|on|for)\\b", Pattern.CASE_INSENSITIVE)
    );

    private static final List<TopicRewriteRule> TOPIC_REWRITE_RULES = List.of(
            new TopicRewriteRule(List.of("academic calendar", "calendar", "semester date", "校历", "学年"),
                    "academic calendar semester dates registration lecture break examination holiday"),
            new TopicRewriteRule(List.of("timetable", "course schedule", "class schedule", "课程表", "课表", "选课"),
                    "course timetable class schedule course mode room coordinator"),
            new TopicRewriteRule(List.of("visa", "student pass", "emgs", "renewal", "续签", "签证"),
                    "student visa renewal student pass EMGS passport required documents PKP"),
            new TopicRewriteRule(List.of("admission", "apply", "requirement", "programme", "program", "申请", "入学"),
                    "FTSM postgraduate admission programme requirements application intake"),
            new TopicRewriteRule(List.of("bus", "route", "campus bus", "bas kampus", "巴士", "公交"),
                    "UKM campus bus route schedule stop route information"),
            new TopicRewriteRule(List.of("staff", "lecturer", "supervisor", "advisor", "expertise", "导师", "老师"),
                    "FTSM academic staff lecturer supervisor advisor expertise email"),
            new TopicRewriteRule(List.of("industrial training", "internship", "latihan industri", "实习"),
                    "FTSM industrial training internship coordinator contact requirement"),
            new TopicRewriteRule(List.of("facility", "facilities", "service", "lab", "library", "设施"),
                    "FTSM facilities services laboratory library student facilities"),
            new TopicRewriteRule(List.of("exam", "final exam", "examination", "考试"),
                    "final examination schedule exam date venue course"),
            new TopicRewriteRule(List.of("registration", "register", "enrolment", "renewal", "注册"),
                    "student registration renewal course registration required documents"),
            new TopicRewriteRule(List.of("system", "portal", "smpweb", "folio", "系统"),
                    "UKM student system portal SMPWEB FOLIO academic system")
    );

    private static final Map<String, List<String>> SYNONYM_MAP = new LinkedHashMap<>();

    static {
        // Visa/Student Pass
        SYNONYM_MAP.put("签证", List.of("student pass", "visa", "permit pelajar", "EMGS", "eVisa"));
        SYNONYM_MAP.put("续签", List.of("renew student pass", "visa renewal", "pembaharuan permit pelajar"));
        SYNONYM_MAP.put("准证", List.of("student pass", "permit pelajar", "visa renewal"));
        SYNONYM_MAP.put("student pass", List.of("permit pelajar", "visa renewal", "EMGS", "续签 签证"));
        SYNONYM_MAP.put("permit pelajar", List.of("student pass", "visa renewal", "EMGS", "续签"));
        SYNONYM_MAP.put("visa", List.of("student pass", "permit pelajar", "eVisa", "签证"));
        SYNONYM_MAP.put("emgs", List.of("student pass", "visa", "permit pelajar", "EMGS approval"));

        // Registration/Enrolment
        SYNONYM_MAP.put("注册", List.of("registration", "pendaftaran", "enrolment UKM"));
        SYNONYM_MAP.put("选课", List.of("course registration", "pendaftaran kursus", "timetable add drop"));
        SYNONYM_MAP.put("退课", List.of("drop course", "withdrawal", "tangguh pengajian"));
        SYNONYM_MAP.put("registration", List.of("pendaftaran", "注册", "enrolment", "course registration"));
        SYNONYM_MAP.put("pendaftaran", List.of("registration", "注册", "enrolment UKM"));

        // Degrees/Graduation
        SYNONYM_MAP.put("毕业", List.of("graduation", "konvokesyen", "convocation", "tamat pengajian"));
        SYNONYM_MAP.put("毕业证书", List.of("degree certificate", "sijil ijazah", "transcript"));
        SYNONYM_MAP.put("成绩单", List.of("transcript", "rekod akademik", "result slip", "keputusan"));
        SYNONYM_MAP.put("留服认证", List.of("CSCSE", "credential evaluation", "pengesahan sijil China"));
        SYNONYM_MAP.put("convocation", List.of("konvokesyen", "graduation", "毕业", "graduation ceremony"));
        SYNONYM_MAP.put("konvokesyen", List.of("convocation", "graduation", "毕业典礼"));

        // Supervisor/Staff
        SYNONYM_MAP.put("导师", List.of("supervisor", "penyelia", "advisor", "academic staff FTSM"));
        SYNONYM_MAP.put("教授", List.of("professor", "profesor", "Dr.", "academic staff"));
        SYNONYM_MAP.put("老师", List.of("lecturer", "pensyarah", "tutor", "academic staff FTSM"));
        SYNONYM_MAP.put("supervisor", List.of("penyelia", "导师", "advisor", "academic staff"));
        SYNONYM_MAP.put("pensyarah", List.of("lecturer", "老师", "tutor", "academic staff FTSM"));
        SYNONYM_MAP.put("penyelia", List.of("supervisor", "导师", "advisor FTSM"));
        SYNONYM_MAP.put("staf akademik", List.of("academic staff", "lecturer", "老师 导师"));

        // Programs
        SYNONYM_MAP.put("硕士", List.of("master", "sarjana", "postgraduate", "MSc FTSM"));
        SYNONYM_MAP.put("博士", List.of("PhD", "doktor falsafah", "doctoral", "doctorate FTSM"));
        SYNONYM_MAP.put("本科", List.of("undergraduate", "sarjana muda", "bachelor", "degree FTSM"));
        SYNONYM_MAP.put("人工智能", List.of("artificial intelligence", "kecerdasan buatan", "AI program MSc"));
        SYNONYM_MAP.put("网络安全", List.of("cyber security", "keselamatan siber", "MSc cyber FTSM"));
        SYNONYM_MAP.put("数据科学", List.of("data science", "sains data", "MSc data science FTSM"));
        SYNONYM_MAP.put("软件工程", List.of("software engineering", "kejuruteraan perisian", "MSc SE FTSM"));
        SYNONYM_MAP.put("信息技术", List.of("information technology", "teknologi maklumat", "IT program FTSM"));
        SYNONYM_MAP.put("信息系统", List.of("information systems", "sistem maklumat", "MSc IS FTSM"));
        SYNONYM_MAP.put("计算机科学", List.of("computer science", "sains komputer", "CS program FTSM"));
        SYNONYM_MAP.put("创意媒体", List.of("creative media technology", "teknologi media kreatif", "MSc CMT"));
        SYNONYM_MAP.put("sarjana", List.of("master", "硕士", "postgraduate", "MSc"));
        SYNONYM_MAP.put("sarjana muda", List.of("bachelor", "undergraduate", "本科", "degree program"));
        SYNONYM_MAP.put("doktor falsafah", List.of("PhD", "博士", "doctoral program FTSM"));
        SYNONYM_MAP.put("kecerdasan buatan", List.of("artificial intelligence", "AI", "人工智能 FTSM"));
        SYNONYM_MAP.put("keselamatan siber", List.of("cyber security", "网络安全", "MSc cyber"));
        SYNONYM_MAP.put("sains data", List.of("data science", "数据科学", "MSc data"));
        SYNONYM_MAP.put("sains komputer", List.of("computer science", "计算机科学", "CS FTSM"));

        // Campus/Facilities
        SYNONYM_MAP.put("图书馆", List.of("library", "perpustakaan", "UKM library"));
        SYNONYM_MAP.put("宿舍", List.of("hostel", "kolej kediaman", "residential college UKM"));
        SYNONYM_MAP.put("食堂", List.of("cafeteria", "kafeteria", "kantin", "DTC"));
        SYNONYM_MAP.put("公交", List.of("bus", "bas kampus", "campus bus UKM route"));
        SYNONYM_MAP.put("巴士", List.of("bus", "bas", "campus bus UKM route"));
        SYNONYM_MAP.put("实验室", List.of("lab", "makmal", "laboratory FTSM"));
        SYNONYM_MAP.put("停车场", List.of("parking", "tempat letak kereta", "car park UKM"));
        SYNONYM_MAP.put("perpustakaan", List.of("library", "图书馆", "UKM library"));
        SYNONYM_MAP.put("kolej kediaman", List.of("residential college", "hostel", "宿舍 UKM"));
        SYNONYM_MAP.put("bas kampus", List.of("campus bus", "公交 巴士", "UKM bus route"));
        SYNONYM_MAP.put("makmal", List.of("lab", "laboratory", "实验室 FTSM"));

        // Holidays/Calendar
        SYNONYM_MAP.put("假期", List.of("public holiday", "cuti umum", "holiday Malaysia"));
        SYNONYM_MAP.put("放假", List.of("public holiday", "cuti semester", "semester break"));
        SYNONYM_MAP.put("开斋节", List.of("Hari Raya Aidilfitri", "Eid", "cuti Hari Raya"));
        SYNONYM_MAP.put("春节", List.of("Chinese New Year", "Tahun Baru Cina", "CNY cuti"));
        SYNONYM_MAP.put("屠妖节", List.of("Deepavali", "Diwali", "cuti Deepavali"));
        SYNONYM_MAP.put("哈芝节", List.of("Hari Raya Aidiladha", "Eid al-Adha", "cuti Aidiladha"));
        SYNONYM_MAP.put("国庆日", List.of("Hari Merdeka", "National Day", "31 Ogos"));
        SYNONYM_MAP.put("cuti umum", List.of("public holiday", "假期", "holiday Malaysia"));
        SYNONYM_MAP.put("hari raya", List.of("Eid", "开斋节", "Aidilfitri", "cuti"));
        SYNONYM_MAP.put("deepavali", List.of("屠妖节", "Diwali", "Indian festival holiday"));

        // Applications
        SYNONYM_MAP.put("申请", List.of("apply", "permohonan", "application admission FTSM"));
        SYNONYM_MAP.put("入学", List.of("admission", "kemasukan", "enrollment intake FTSM"));
        SYNONYM_MAP.put("录取", List.of("offer letter", "surat tawaran", "acceptance admission"));
        SYNONYM_MAP.put("permohonan", List.of("application", "申请", "admission FTSM"));
        SYNONYM_MAP.put("kemasukan", List.of("admission", "入学", "enrollment UKM"));
        SYNONYM_MAP.put("surat tawaran", List.of("offer letter", "录取通知", "acceptance letter UKM"));

        // Portals & Systems
        SYNONYM_MAP.put("系统", List.of("system", "sistem", "portal UKM platform"));
        SYNONYM_MAP.put("学生系统", List.of("student portal", "portal pelajar", "SMP system UKM"));
        SYNONYM_MAP.put("成绩", List.of("result", "keputusan", "grade CGPA GPA"));
        SYNONYM_MAP.put("学费", List.of("tuition fee", "yuran pengajian", "fees bayaran"));
        SYNONYM_MAP.put("portal pelajar", List.of("student portal", "学生系统", "UKM student system"));
        SYNONYM_MAP.put("sistem", List.of("system", "系统", "portal UKM"));
        SYNONYM_MAP.put("yuran", List.of("fees", "学费", "tuition fee UKM"));
        SYNONYM_MAP.put("keputusan", List.of("result", "成绩", "grade CGPA"));

        // Internship
        SYNONYM_MAP.put("实习", List.of("industrial training", "latihan industri", "internship FTSM"));
        SYNONYM_MAP.put("工业培训", List.of("industrial training", "latihan industri FTSM"));
        SYNONYM_MAP.put("latihan industri", List.of("industrial training", "实习", "internship FTSM"));
        SYNONYM_MAP.put("internship", List.of("latihan industri", "实习", "industrial training FTSM"));

        // Contact
        SYNONYM_MAP.put("联系", List.of("contact", "hubungi", "email phone FTSM"));
        SYNONYM_MAP.put("电话", List.of("phone", "nombor telefon", "contact number FTSM"));
        SYNONYM_MAP.put("邮箱", List.of("email", "e-mel", "contact FTSM"));
        SYNONYM_MAP.put("办公室", List.of("office", "pejabat", "FTSM office"));
        SYNONYM_MAP.put("hubungi", List.of("contact", "联系", "phone email FTSM"));
        SYNONYM_MAP.put("pejabat", List.of("office", "办公室", "FTSM office"));

        // Financial
        SYNONYM_MAP.put("奖学金", List.of("scholarship", "biasiswa", "financial aid UKM"));
        SYNONYM_MAP.put("助学金", List.of("bursary", "bantuan kewangan", "financial assistance"));
        SYNONYM_MAP.put("biasiswa", List.of("scholarship", "奖学金", "financial aid UKM"));
        SYNONYM_MAP.put("bantuan kewangan", List.of("financial aid", "助学金 奖学金", "bursary UKM"));

        // Research & Thesis
        SYNONYM_MAP.put("论文", List.of("thesis", "tesis", "dissertation research FTSM"));
        SYNONYM_MAP.put("毕业论文", List.of("final year project", "FYP", "tesis sarjana muda"));
        SYNONYM_MAP.put("研究", List.of("research", "penyelidikan", "research FTSM"));
        SYNONYM_MAP.put("tesis", List.of("thesis", "论文", "dissertation FTSM"));
        SYNONYM_MAP.put("penyelidikan", List.of("research", "研究", "research center FTSM"));
        SYNONYM_MAP.put("fyp", List.of("final year project", "毕业论文", "tesis sarjana muda FTSM"));

        // Student Affairs
        SYNONYM_MAP.put("学生事务", List.of("student affairs", "hal ehwal pelajar", "HEP FTSM"));
        SYNONYM_MAP.put("hal ehwal pelajar", List.of("student affairs", "学生事务", "HEP FTSM"));
        SYNONYM_MAP.put("hejim", List.of("hal ehwal jaringan industri masyarakat", "industry engagement"));

        // Mobility
        SYNONYM_MAP.put("交流", List.of("mobility", "exchange", "program pertukaran pelajar"));
        SYNONYM_MAP.put("交换生", List.of("exchange student", "pelajar pertukaran", "mobility program UKM"));
        SYNONYM_MAP.put("mobility", List.of("pertukaran pelajar", "交流 交换", "exchange program UKM"));
    }

    public String traditionalToSimplified(String text) {
        if (text == null) return null;
        try {
            return ZhConverterUtil.toSimple(text);
        } catch (Exception e) {
            log.warn("ZhConverterUtil failed to translate traditional to simplified: {}", e.getMessage());
            return text;
        }
    }

    public String normalize(String text) {
        if (text == null) return null;
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return normalized.replaceAll("\\s+", " ").trim();
    }

    public List<String> rewriteStudentQuery(String text) {
        List<String> rewrites = new ArrayList<>();
        String cleaned = text;
        for (Pattern p : NAVIGATION_PATTERNS) {
            cleaned = p.matcher(cleaned).replaceAll(" ");
        }
        cleaned = cleaned.replaceAll("[?？。!！]+", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        // Remove trailing punctuation
        cleaned = cleaned.replaceAll("^[,.;:]+|[,.;:]+$", "");

        if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase(text)) {
            rewrites.add(cleaned);
        }

        String textLower = text.toLowerCase();
        for (TopicRewriteRule rule : TOPIC_REWRITE_RULES) {
            for (String keyword : rule.keywords) {
                if (textLower.contains(keyword.toLowerCase())) {
                    if (!rewrites.contains(rule.rewrite)) {
                        rewrites.add(rule.rewrite);
                    }
                    break;
                }
            }
        }
        return rewrites;
    }

    public List<String> expandSynonyms(String text) {
        List<String> expansions = new ArrayList<>();
        String textLower = text.toLowerCase();

        for (Map.Entry<String, List<String>> entry : SYNONYM_MAP.entrySet()) {
            if (textLower.contains(entry.getKey().toLowerCase())) {
                List<String> synonyms = entry.getValue();
                for (int i = 0; i < Math.min(synonyms.size(), maxExpansions); i++) {
                    String syn = synonyms.get(i);
                    String candidate = (text + " " + syn).trim();
                    if (!expansions.contains(candidate) && !candidate.equalsIgnoreCase(text)) {
                        expansions.add(candidate);
                    }
                }
                if (expansions.size() >= maxExpansions) {
                    break;
                }
            }
        }
        return expansions;
    }

    public List<String> process(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String simplified = traditionalToSimplified(query);
        String normalized = normalize(simplified);
        List<String> rewrites = rewriteStudentQuery(normalized);
        List<String> expansions = expandSynonyms(normalized);

        List<String> queries = new ArrayList<>();
        queries.add(normalized);
        for (String r : rewrites) {
            String normR = normalize(r);
            if (!normR.isEmpty() && !queries.contains(normR)) {
                queries.add(normR);
            }
        }
        for (String e : expansions) {
            String normE = normalize(e);
            if (!normE.isEmpty() && !queries.contains(normE)) {
                queries.add(normE);
            }
        }

        if (queries.size() > 1 + maxExpansions) {
            return queries.subList(0, 1 + maxExpansions);
        }
        return queries;
    }

    private static class TopicRewriteRule {
        final List<String> keywords;
        final String rewrite;

        TopicRewriteRule(List<String> keywords, String rewrite) {
            this.keywords = keywords;
            this.rewrite = rewrite;
        }
    }
}
