package com.ftsm.rag.utils;

import com.ftsm.rag.service.ModelFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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

    private final ModelFactory modelFactory;

    private static final String[] TEXT_ENCODINGS = {"UTF-8", "GB18030", "BIG5"};
    private static final String[] MOJIBAKE_MARKERS = {
            "锛", "鐨", "璇", "绋", "鈹", "鉁", ""
    };

    public FileExtractors(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
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

    public Mono<List<Document>> pdfLoader(Path path) {
        return Mono.fromCallable(() -> {
            List<Document> textDocs = new ArrayList<>();
            List<Mono<List<Document>>> imageTasks = new ArrayList<>();
            try (PDDocument document = Loader.loadPDF(path.toFile())) {
                int pageCount = document.getNumberOfPages();
                int totalTextChars = 0;
                for (int i = 1; i <= pageCount; i++) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setStartPage(i);
                    stripper.setEndPage(i);
                    String pageText = stripper.getText(document);
                    
                    if (pageText != null && !pageText.trim().isEmpty()) {
                        totalTextChars += pageText.trim().length();
                        Metadata metadata = new Metadata();
                        metadata.put("source", path.toAbsolutePath().toString());
                        metadata.put("filename", path.getFileName().toString());
                        metadata.put("page", String.valueOf(i));
                        textDocs.add(Document.from(pageText, metadata));
                    }

                    PDPage page = document.getPage(i - 1);
                    PDResources resources = page.getResources();
                    if (resources != null) {
                        int imageIndex = 1;
                        for (COSName name : resources.getXObjectNames()) {
                            if (resources.isImageXObject(name)) {
                                try {
                                    PDImageXObject image = (PDImageXObject) resources.getXObject(name);
                                    BufferedImage bImage = image.getImage();
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    ImageIO.write(bImage, "jpeg", baos);
                                    byte[] imageBytes = baos.toByteArray();
                                    String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                                    
                                    String imageName = path.getFileName().toString() + "_page" + i + "_img" + imageIndex++;
                                    imageTasks.add(processImageBytes(base64Image, imageName, bImage.getWidth(), bImage.getHeight(), path.toAbsolutePath().toString(), String.valueOf(i)));
                                } catch (Exception ex) {
                                    log.warn("Failed to extract image from {} page {}", path.getFileName(), i, ex);
                                }
                            }
                        }
                    }
                }
                
                if (totalTextChars < 80 && imageTasks.isEmpty()) {
                    log.warn("[pdf_loader] {} appears to be empty or unscannable ({} text chars).",
                            path.getFileName(), totalTextChars);
                    return new PdfExtractResult(Collections.<Document>emptyList(), Collections.<Mono<List<Document>>>emptyList());
                }
                return new PdfExtractResult(textDocs, imageTasks);
            } catch (IOException e) {
                log.error("[pdf_loader] Failed to load PDF: {}", path, e);
                return new PdfExtractResult(Collections.<Document>emptyList(), Collections.<Mono<List<Document>>>emptyList());
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).flatMap(result -> {
            if (result.imageTasks.isEmpty()) {
                return Mono.just(result.textDocs);
            }
            return reactor.core.publisher.Flux.merge(result.imageTasks)
                    .reduce(new ArrayList<Document>(result.textDocs), (allDocs, imgDocs) -> {
                        allDocs.addAll(imgDocs);
                        return allDocs;
                    });
        });
    }

    public Mono<List<Document>> structuredPdfLoader(Path path) {
        return Mono.fromCallable(() -> {
            List<Document> textDocs = new ArrayList<>();
            List<Mono<List<Document>>> imageTasks = new ArrayList<>();
            try (PDDocument document = Loader.loadPDF(path.toFile())) {
                int pageCount = document.getNumberOfPages();
                int totalTextChars = 0;
                String currentH1 = "";
                String currentH2 = "";
                String currentH3 = "";

                for (int i = 1; i <= pageCount; i++) {
                    final int pageNum = i;
                    PDFTextStripper stripper = new PDFTextStripper() {
                        @Override
                        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
                            if (textPositions != null && !textPositions.isEmpty()) {
                                TextPosition firstPosition = textPositions.get(0);
                                float fontSize = firstPosition.getFontSizeInPt();

                                String prefix = "";
                                if (fontSize >= 16.0f) {
                                    prefix = "# ";
                                } else if (fontSize >= 13.0f) {
                                    prefix = "## ";
                                } else if (fontSize >= 11.0f) {
                                    prefix = "### ";
                                }
                                
                                if (text.contains("|") || text.contains("\t")) {
                                    // Basic table row detection heuristic
                                    // Could format as Markdown table row, but for now we just keep the | chars
                                }
                                
                                super.writeString(prefix + text, textPositions);
                            } else {
                                super.writeString(text, textPositions);
                            }
                        }
                    };
                    stripper.setStartPage(i);
                    stripper.setEndPage(i);
                    String pageText = stripper.getText(document);

                    if (pageText != null && !pageText.trim().isEmpty()) {
                        totalTextChars += pageText.trim().length();
                        
                        // Extract dynamic heading path based on the markdown headers inserted
                        for (String line : pageText.split("\n")) {
                            if (line.startsWith("# ")) {
                                currentH1 = line.substring(2).trim();
                                currentH2 = "";
                                currentH3 = "";
                            } else if (line.startsWith("## ")) {
                                currentH2 = line.substring(3).trim();
                                currentH3 = "";
                            } else if (line.startsWith("### ")) {
                                currentH3 = line.substring(4).trim();
                            }
                        }
                        
                        String headingPath = currentH1;
                        if (!currentH2.isEmpty()) headingPath += "/" + currentH2;
                        if (!currentH3.isEmpty()) headingPath += "/" + currentH3;

                        Metadata metadata = new Metadata();
                        metadata.put("source", path.toAbsolutePath().toString());
                        metadata.put("filename", path.getFileName().toString());
                        metadata.put("source_page", String.valueOf(i));
                        if (!headingPath.isEmpty()) {
                            metadata.put("heading_path", headingPath);
                        }
                        textDocs.add(Document.from(pageText, metadata));
                    }

                    // Image extraction
                    PDPage page = document.getPage(i - 1);
                    PDResources resources = page.getResources();
                    if (resources != null) {
                        int imageIndex = 1;
                        for (COSName name : resources.getXObjectNames()) {
                            if (resources.isImageXObject(name)) {
                                try {
                                    PDImageXObject image = (PDImageXObject) resources.getXObject(name);
                                    BufferedImage bImage = image.getImage();
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    ImageIO.write(bImage, "jpeg", baos);
                                    byte[] imageBytes = baos.toByteArray();
                                    String base64Image = Base64.getEncoder().encodeToString(imageBytes);

                                    String imageName = path.getFileName().toString() + "_page" + i + "_img" + imageIndex++;
                                    imageTasks.add(processImageBytes(base64Image, imageName, bImage.getWidth(), bImage.getHeight(), path.toAbsolutePath().toString(), String.valueOf(i)));
                                } catch (Exception ex) {
                                    log.warn("Failed to extract image from {} page {}", path.getFileName(), i, ex);
                                }
                            }
                        }
                    }
                }

                if (totalTextChars < 80 && imageTasks.isEmpty()) {
                    log.warn("[structured_pdf_loader] {} appears to be empty or unscannable ({} text chars).",
                            path.getFileName(), totalTextChars);
                    return new PdfExtractResult(Collections.<Document>emptyList(), Collections.<Mono<List<Document>>>emptyList());
                }
                return new PdfExtractResult(textDocs, imageTasks);
            } catch (IOException e) {
                log.error("[structured_pdf_loader] Failed to load PDF: {}", path, e);
                return new PdfExtractResult(Collections.<Document>emptyList(), Collections.<Mono<List<Document>>>emptyList());
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).flatMap(result -> {
            if (result.imageTasks.isEmpty()) {
                return Mono.just(result.textDocs);
            }
            return reactor.core.publisher.Flux.merge(result.imageTasks)
                    .reduce(new ArrayList<Document>(result.textDocs), (allDocs, imgDocs) -> {
                        allDocs.addAll(imgDocs);
                        return allDocs;
                    });
        });
    }

    private record PdfExtractResult(List<Document> textDocs, List<Mono<List<Document>>> imageTasks) {}

    private Mono<List<Document>> processImageBytes(String base64Image, String filename, int width, int height, String sourcePath, String pageNum) {
        String promptText = "请仔细分析这张图片，并提取其中所有可读的文字内容和图表信息。图片文件名: FILENAME。图片尺寸: WIDTHxHEIGHT像素。\n" +
                "请按以下格式输出:\n" +
                "1 如果图片包含图表（如折线图、柱状图、饼图等），请详细描述其数据趋势、比较结果和图形含义（例如：'这是一张招生趋势折线图，显示2020年有300人注册，2021年增加到350人'）。\n" +
                "2 如果图片包含表格请用Markdown表格格式呈现。\n" +
                "3 如果图片包含步骤说明请列出所有步骤。\n" +
                "4 如果图片包含表单字段请列出字段名和示例值。\n" +
                "5 保留原文的所有细节包括数字日期网址等。\n" +
                "请直接输出提取和分析的内容，不要有其他解释。";
        promptText = promptText.replace("FILENAME", filename)
                .replace("WIDTH", String.valueOf(width))
                .replace("HEIGHT", String.valueOf(height));
        
        String finalPromptText = promptText;
        return Mono.fromCallable(() -> {
            UserMessage userMessage = UserMessage.from(
                    dev.langchain4j.data.message.ImageContent.from(base64Image, "image/jpeg"),
                    dev.langchain4j.data.message.TextContent.from(finalPromptText)
            );
            return modelFactory.getVisionModel().generate(userMessage).content().text();
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .map(extractedText -> {
            if (extractedText == null || extractedText.isEmpty()) {
                return Collections.<Document>emptyList();
            }
            Metadata metadata = new Metadata();
            metadata.put("source", sourcePath);
            metadata.put("filename", filename);
            if (pageNum != null) {
                metadata.put("page", pageNum);
            }
            metadata.put("type", "image");
            metadata.put("image_extracted", "true");
            metadata.put("image_width", String.valueOf(width));
            metadata.put("image_height", String.valueOf(height));

            log.info("[image_loader] Successfully extracted text from image: {}", filename);
            return List.of(Document.from(extractedText, metadata));
        }).onErrorResume(e -> {
            log.error("Failed to extract text from image {}", filename, e);
            return Mono.just(Collections.<Document>emptyList());
        });
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
        }).flatMap(ctx -> Mono.fromCallable(() -> {
                    UserMessage userMessage = UserMessage.from(
                            ImageContent.from(ctx.base64Image, "image/jpeg"),
                            TextContent.from(ctx.promptText)
                    );
                    return modelFactory.getChatModel().generate(userMessage).content().text();
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(extractedText -> {
                    if (extractedText == null || extractedText.isEmpty()) {
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
