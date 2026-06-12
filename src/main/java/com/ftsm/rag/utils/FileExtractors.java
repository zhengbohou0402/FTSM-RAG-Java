package com.ftsm.rag.utils;

import com.ftsm.rag.service.DashScopeHttpClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Slf4j
@Component
public class FileExtractors {

    private final DashScopeHttpClient dashScopeHttpClient;

    private static final String[] TEXT_ENCODINGS = {"UTF-8", "GB18030", "BIG5"};
    private static final String[] MOJIBAKE_MARKERS = {
            "锛", "鐨", "璇", "绋", "鈹", "鉁", "�"
    };

    public FileExtractors(DashScopeHttpClient dashScopeHttpClient) {
        this.dashScopeHttpClient = dashScopeHttpClient;
    }

    public static String getFileSha256Hex(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(path);
            byte[] hash = digest.digest(fileBytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("Failed to calculate SHA-256 for {}", path, e);
            return "";
        }
    }

    public static String getFileMd5Hex(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] fileBytes = Files.readAllBytes(path);
            byte[] hash = digest.digest(fileBytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("Failed to calculate MD5 for {}", path, e);
            return "";
        }
    }

    private static int getMojibakeScore(String text) {
        int score = 0;
        for (String marker : MOJIBAKE_MARKERS) {
            int index = 0;
            while ((index = text.indexOf(marker, index)) != -1) {
                score++;
                index += marker.length();
            }
        }
        return score;
    }

    public static DecodedText readTextSafely(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            DecodedText best = null;
            for (String encoding : TEXT_ENCODINGS) {
                try {
                    Charset charset = Charset.forName(encoding);
                    String decoded = charset.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
                    if (decoded.startsWith("\uFEFF")) {
                        decoded = decoded.substring(1);
                    }
                    int score = getMojibakeScore(decoded);
                    DecodedText dt = new DecodedText(decoded, encoding, score);
                    if (best == null || dt.mojibakeScore < best.mojibakeScore) {
                        best = dt;
                    }
                    if (dt.mojibakeScore == 0) {
                        return dt;
                    }
                } catch (Exception ignored) {
                }
            }
            if (best != null) {
                return best;
            }
            // Fallback UTF-8 replace
            String fallbackText = new String(bytes, StandardCharsets.UTF_8);
            return new DecodedText(fallbackText, "UTF-8-replace", getMojibakeScore(fallbackText));
        } catch (IOException e) {
            log.error("Failed to read file {}", path, e);
            return new DecodedText("", "error", 0);
        }
    }

    public List<Document> pdfLoader(Path path) {
        List<Document> docs = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            int pageCount = document.getNumberOfPages();
            int totalTextChars = 0;
            for (int i = 1; i <= pageCount; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(document);
                if (pageText == null || pageText.trim().isEmpty()) {
                    continue;
                }
                totalTextChars += pageText.trim().length();

                Metadata metadata = new Metadata();
                metadata.put("source", path.toAbsolutePath().toString());
                metadata.put("filename", path.getFileName().toString());
                metadata.put("page", String.valueOf(i));

                docs.add(Document.from(pageText, metadata));
            }

            if (totalTextChars < 80) {
                log.warn("[pdf_loader] {} appears to be image-only or scanned ({} text chars).",
                        path.getFileName(), totalTextChars);
                return Collections.emptyList();
            }
        } catch (IOException e) {
            log.error("[pdf_loader] Failed to load PDF: {}", path, e);
        }
        return docs;
    }

    public List<Document> txtLoader(Path path) {
        DecodedText decoded = readTextSafely(path);
        if (decoded.mojibakeScore > 0) {
            log.warn("[txt_loader] Possible mojibake in {} after decoding as {} (score={}).",
                    path.getFileName(), decoded.encoding, decoded.mojibakeScore);
        }
        
        Metadata metadata = new Metadata();
        metadata.put("source", path.toAbsolutePath().toString());
        metadata.put("filename", path.getFileName().toString());
        metadata.put("encoding", decoded.encoding);
        metadata.put("mojibake_score", String.valueOf(decoded.mojibakeScore));

        return List.of(Document.from(decoded.text, metadata));
    }

    public Mono<List<Document>> imageLoader(Path path) {
        return Mono.fromCallable(() -> {
            try {
                byte[] bytes = Files.readAllBytes(path);
                String base64Image = Base64.getEncoder().encodeToString(bytes);
                String filename = path.getFileName().toString();

                int width = 0;
                int height = 0;
                try {
                    BufferedImage img = ImageIO.read(path.toFile());
                    if (img != null) {
                        width = img.getWidth();
                        height = img.getHeight();
                    }
                } catch (Exception e) {
                    log.warn("Failed to get image dimensions for {}", filename, e);
                }

                String promptText = "请仔细分析这张图片，并提取其中所有可读的文字内容。图片文件名: FILENAME。图片尺寸: WIDTHxHEIGHT像素。请按以下格式输出: 1 如果图片包含表格请用表格格式呈现 2 如果图片包含步骤说明请列出所有步骤 3 如果图片包含表单字段请列出字段名和示例值 4 保留原文的所有细节包括数字日期网址等 5 如果图片是截图请标注关键UI元素的位置如右上角高亮显示等。请直接输出提取的文字，不要有其他解释。";
                promptText = promptText.replace("FILENAME", filename)
                        .replace("WIDTH", String.valueOf(width))
                        .replace("HEIGHT", String.valueOf(height));

                return new ImageExtractionContext(base64Image, promptText, filename, width, height);
            } catch (IOException e) {
                log.error("Failed to read image {}", path, e);
                throw new RuntimeException(e);
            }
        }).flatMap(ctx -> dashScopeHttpClient.extractTextFromImage(ctx.base64Image, ctx.promptText)
                .map(extractedText -> {
                    if (extractedText.isEmpty()) {
                        return Collections.<Document>emptyList();
                    }
                    Metadata metadata = new Metadata();
                    metadata.put("source", path.toAbsolutePath().toString());
                    metadata.put("filename", ctx.filename);
                    metadata.put("type", "image");
                    metadata.put("image_extracted", "true");
                    metadata.put("image_width", String.valueOf(ctx.width));
                    metadata.put("image_height", String.valueOf(ctx.height));

                    log.info("[image_loader] Successfully extracted text from: {}", ctx.filename);
                    return List.of(Document.from(extractedText, metadata));
                }))
                .onErrorResume(e -> {
                    log.error("Failed to extract text from image {}", path, e);
                    return Mono.just(Collections.emptyList());
                });
    }

    @Data
    private static class ImageExtractionContext {
        private final String base64Image;
        private final String promptText;
        private final String filename;
        private final int width;
        private final int height;
    }

    @Data
    public static class DecodedText {
        private final String text;
        private final String encoding;
        private final int mojibakeScore;
    }
}
