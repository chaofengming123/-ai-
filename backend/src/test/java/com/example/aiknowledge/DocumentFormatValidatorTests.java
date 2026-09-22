package com.example.aiknowledge;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.aiknowledge.service.DocumentFormatValidator;
import com.example.aiknowledge.exception.DocumentException;
import static org.junit.jupiter.api.Assertions.*;

class DocumentFormatValidatorTests {
    @Test void acceptsPdfAndDocxWithoutRewritingOriginalBytes() throws Exception {
        for(String type:List.of("pdf","docx")) {
            byte[] bytes=type.equals("pdf")?DocumentFixtures.pdf(1,false):DocumentFixtures.docx();
            byte[] original=bytes.clone();
            assertDoesNotThrow(()->DocumentFormatValidator.validate(type,bytes));
            assertArrayEquals(original,bytes);
        }
    }
    @Test void rejectsRenamedAndBrokenFiles() throws Exception {
        for(String type:List.of("pdf","docx"))
            assertThrows(DocumentException.class,()->DocumentFormatValidator.validate(type,"just text".getBytes(StandardCharsets.UTF_8)));
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("pdf","%PDF-1.7\nnot a PDF\n%%EOF".getBytes(StandardCharsets.UTF_8)));
        byte[] zip=DocumentFixtures.docx();
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",Arrays.copyOf(zip,zip.length-22)));
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",DocumentFixtures.zip(Map.of("other.txt","hello"))));
    }
    @Test void rejectsEncryptedEmptyAndExcessivePagePdf() throws Exception {
        for(byte[] bytes:List.of(DocumentFixtures.pdf(1,true),DocumentFixtures.pdf(0,false),DocumentFixtures.pdf(501,false)))
            assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("pdf",bytes));
    }
    @Test void rejectsWrongWordMainPartMissingRelationsAndMacros() throws Exception {
        var missing=DocumentFixtures.docxParts(); missing.remove("_rels/.rels");
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",DocumentFixtures.zip(missing)));
        var wrong=DocumentFixtures.docxParts(); wrong.put("word/document.xml","<not-word/>");
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",DocumentFixtures.zip(wrong)));
        var macro=DocumentFixtures.docxParts(); macro.put("word/vbaProject.bin","test-only");
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",DocumentFixtures.zip(macro)));
        var external=DocumentFixtures.docxParts(); external.put("_rels/.rels",external.get("_rels/.rels").replace("Target=","TargetMode=\"External\" Target="));
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",DocumentFixtures.zip(external)));
    }
    @Test void rejectsDoctypeExternalEntitiesAndDeepXml() throws Exception {
        var xxe=DocumentFixtures.docxParts();
        xxe.put("word/document.xml","<!DOCTYPE w:document [<!ENTITY x SYSTEM 'file:///must-not-read'>]>"+xxe.get("word/document.xml").replace("第二十课：原文件保持不变。","&x;"));
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",DocumentFixtures.zip(xxe)));
        var deep=DocumentFixtures.docxParts(); deep.put("word/document.xml","<a>".repeat(150)+"text"+"</a>".repeat(150));
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",DocumentFixtures.zip(deep)));
    }
    @Test void boundsExpandedArchiveSizeEntryCountAndPaths() throws Exception {
        var huge=DocumentFixtures.docxParts(); huge.put("word/large.bin","a".repeat(4*1024*1024+1));
        byte[] zip=DocumentFixtures.zip(huge); assertTrue(zip.length<1024*1024);
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",zip));
        var many=DocumentFixtures.docxParts(); for(int i=0;i<130;i++) many.put("extra/"+i,"x");
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",DocumentFixtures.zip(many)));
        var traversal=DocumentFixtures.docxParts(); traversal.put("../escape.txt","x");
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",DocumentFixtures.zip(traversal)));
        var total=DocumentFixtures.docxParts();
        for(int i=0;i<3;i++) total.put("extra/"+i,"x".repeat(3*1024*1024));
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("docx",DocumentFixtures.zip(total)));
    }
}
