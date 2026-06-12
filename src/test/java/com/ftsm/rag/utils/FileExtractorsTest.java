package com.ftsm.rag.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FileExtractorsTest {

    @TempDir
    Path tempDir;

    @Test
    void doesNotAcceptInvalidUtf8AsUtf8() throws Exception {
        String expected = "课程注册与签证续签";
        Path file = tempDir.resolve("gb18030.txt");
        Files.write(file, expected.getBytes(Charset.forName("GB18030")));

        FileExtractors.DecodedText decoded = FileExtractors.readTextSafely(file);

        assertEquals(expected, decoded.getText());
        assertNotEquals("UTF-8", decoded.getEncoding());
    }
}
