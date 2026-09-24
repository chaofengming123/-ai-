package com.example.aiknowledge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import com.example.aiknowledge.service.*;
import com.example.aiknowledge.exception.DocumentException;
import static org.junit.jupiter.api.Assertions.*;

class LegacyWordTests {
    static byte[] fixture() throws IOException {
        try(var input=LegacyWordTests.class.getResourceAsStream("/doc-fixtures/simple.doc")) {
            if(input==null) throw new IOException("Missing DOC fixture");
            return input.readAllBytes();
        }
    }
    @Test void realWordBinaryCanBeValidatedPreviewedAndIndexed() throws Exception {
        byte[] bytes=fixture(), original=bytes.clone();
        DocumentFormatValidator.validate("doc",bytes);
        var preview=DocumentTextExtractor.extract("sample.DOC",bytes);
        assertFalse(preview.content().isBlank());
        assertTrue(preview.content().contains("This is a simple file"),preview.content());
        assertEquals(preview.content(),DocumentTextExtractor.forIndex("sample.doc",bytes).content());
        assertArrayEquals(original,bytes);
    }
    @Test void rejectsRenamedAndTruncatedFiles() throws Exception {
        for(byte[] bytes:new byte[][]{"fake Word".getBytes(StandardCharsets.UTF_8),DocumentFixtures.docx(),Arrays.copyOf(fixture(),512)})
            assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("doc",bytes));
        try(var fs=new POIFSFileSystem(); var out=new ByteArrayOutputStream()) {
            fs.createDocument(new ByteArrayInputStream(new byte[]{1,2,3}),"Workbook");
            fs.writeFilesystem(out);
            assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("doc",out.toByteArray()));
        }
    }
    @Test void rejectsMacroStorageBeforeExtractingText() throws Exception {
        try(var fs=new POIFSFileSystem(new ByteArrayInputStream(fixture())); var out=new ByteArrayOutputStream()) {
            fs.getRoot().createDirectory("Macros"); fs.writeFilesystem(out);
            var error=assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("doc",out.toByteArray()));
            assertTrue(error.getMessage().contains("宏"));
        }
    }
    @Test void rejectsEncryptedFlagBeforeReturningContent() throws Exception {
        try(var fs=new POIFSFileSystem(new ByteArrayInputStream(fixture())); var out=new ByteArrayOutputStream()) {
            byte[] word;
            try(var input=fs.createDocumentInputStream("WordDocument")) { word=input.readAllBytes(); }
            word[11]=(byte)(word[11] | 1); // FibBase fEncrypted flag at bit 8 of the offset-10 flags.
            fs.getRoot().createOrUpdateDocument("WordDocument",new ByteArrayInputStream(word));
            fs.writeFilesystem(out);
            assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("doc",out.toByteArray()));
        }
    }
    @Test void existingPreviewAndIndexLimitsApplyToWordText() throws Exception {
        try(var doc=new HWPFDocument(new ByteArrayInputStream(fixture())); var out=new ByteArrayOutputStream()) {
            doc.getRange().insertBefore("测试正文".repeat(10001)); doc.write(out);
            var preview=DocumentTextExtractor.extract("large.doc",out.toByteArray());
            assertTrue(preview.truncated()); assertEquals(40000,preview.content().length());
            assertThrows(DocumentException.class,()->DocumentTextExtractor.forIndex("large.doc",out.toByteArray()));
        }
    }
}
