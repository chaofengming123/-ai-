package com.example.aiknowledge;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.aiknowledge.service.*;
import com.example.aiknowledge.exception.DocumentException;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedDocumentTests {
    byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Test void structuredTextCanBeIndexedAndKeepsItsFields() {
        for(var entry:Map.of("csv","姓名,流程\n小王,审批", "tsv","姓名\t流程\n小王\t审批", "json","{\"流程\":\"审批\"}").entrySet()) {
            DocumentFormatValidator.validate(entry.getKey(),bytes(entry.getValue()));
            assertTrue(DocumentTextExtractor.forIndex("test."+entry.getKey(),bytes(entry.getValue())).content().contains("审批"));
        }
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("json",bytes("{invalid}")));
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("json",bytes("{} {}")));
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("csv",new byte[]{(byte)0xff}));
    }
    @Test void htmlOnlyExtractsTextWithoutScriptsOrExternalResources() {
        var result=DocumentTextExtractor.forIndex("test.html",bytes("<html><head><style>evil-style</style></head><body><p>审批流程</p><script>evil-script</script><img src='http://127.0.0.1:1/private'><p>提交申请</p></body></html>"));
        assertTrue(result.content().contains("审批流程")); assertTrue(result.content().contains("提交申请"));
        assertFalse(result.content().contains("evil")); assertFalse(result.content().contains("private"));
    }
    @Test void rtfExtractsTextAndRejectsRenamedFiles() {
        assertTrue(DocumentTextExtractor.forIndex("test.rtf",bytes("{\\rtf1\\ansi Hello approval\\par Next line}")).content().contains("Hello approval"));
        assertThrows(DocumentException.class,()->DocumentFormatValidator.validate("rtf",bytes("just text")));
    }
    @Test void newFormatsRespectIndexAndPreviewBounds() {
        assertThrows(DocumentException.class,()->DocumentTextExtractor.forIndex("test.html",bytes("<p>"+"a".repeat(4001)+"</p>")));
        assertThrows(DocumentException.class,()->DocumentTextExtractor.forIndex("test.rtf",bytes("{\\rtf1 "+"a".repeat(4001)+"}")));
        var preview=DocumentTextExtractor.extract("test.csv",bytes("a".repeat(40001)));
        assertTrue(preview.truncated()); assertEquals(40000,preview.content().length());
    }
}
