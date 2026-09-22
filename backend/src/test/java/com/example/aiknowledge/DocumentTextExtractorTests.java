package com.example.aiknowledge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import com.example.aiknowledge.service.DocumentTextExtractor;
import com.example.aiknowledge.exception.DocumentException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentTextExtractorTests {
    @Test void textPreservesMarkdownAndNormalizesBomAndNewlines() {
        var result=DocumentTextExtractor.extract("note.MD","\uFEFF# 标题\r\n正文🙂".getBytes(StandardCharsets.UTF_8));
        assertEquals("# 标题\n正文🙂",result.content()); assertFalse(result.truncated());
    }
    @Test void truncationDoesNotLeaveHalfASurrogatePair() {
        var result=DocumentTextExtractor.extract("note.txt",("a".repeat(39999)+"🙂end").getBytes(StandardCharsets.UTF_8));
        assertEquals(39999,result.content().length()); assertTrue(result.truncated());
    }
    @Test void wordParagraphsTablesAndBreaksAreExtractedButDeletedTextIsExcluded() throws Exception {
        var parts=DocumentFixtures.docxParts();
        parts.put("word/document.xml","""
          <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
          <w:p><w:r><w:t>你好</w:t><w:tab/><w:t>世界</w:t><w:br/><w:t>下一行</w:t></w:r>
          <w:del><w:r><w:t>已删除</w:t></w:r></w:del></w:p>
          <w:tbl><w:tr><w:tc><w:p><w:r><w:t>单元格</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
          </w:body></w:document>""");
        var result=DocumentTextExtractor.extract("a.docx",DocumentFixtures.zip(parts));
        assertTrue(result.content().startsWith("你好\t世界\n下一行\n"));
        assertTrue(result.content().contains("单元格")); assertFalse(result.content().contains("已删除"));
        parts.put("word/document.xml",parts.get("word/document.xml").replace("http://schemas.openxmlformats.org/wordprocessingml/2006/main","http://purl.oclc.org/ooxml/wordprocessingml/main"));
        assertEquals(result.content(),DocumentTextExtractor.extract("a.docx",DocumentFixtures.zip(parts)).content());
    }
    @Test void pdfTextLayerAndEmptyPagesHaveDistinctResults() throws Exception {
        try(var pdf=new PDDocument();var output=new ByteArrayOutputStream()) {
            var page=new PDPage(); pdf.addPage(page);
            try(var stream=new PDPageContentStream(pdf,page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(50,700); stream.showText("Lesson 23 text layer"); stream.endText();
            }
            pdf.save(output);
            assertTrue(DocumentTextExtractor.extract("a.pdf",output.toByteArray()).content().contains("Lesson 23 text layer"));
        }
        var blank=DocumentTextExtractor.extract("blank.pdf",DocumentFixtures.pdf(1,false));
        assertEquals("",blank.content()); assertTrue(blank.note().contains("未提取到"));
        assertTrue(DocumentTextExtractor.extract("long.pdf",DocumentFixtures.pdf(21,false)).truncated());
    }
    @Test void extractionRetainsFormatAndXmlProtections() throws Exception {
        assertThrows(DocumentException.class,()->DocumentTextExtractor.extract("a.pdf","not pdf".getBytes()));
        assertThrows(DocumentException.class,()->DocumentTextExtractor.extract("a.txt",new byte[]{(byte)0xff}));
        var parts=DocumentFixtures.docxParts();
        parts.put("word/document.xml","<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///must-not-read'>]>"+parts.get("word/document.xml"));
        assertThrows(DocumentException.class,()->DocumentTextExtractor.extract("a.docx",DocumentFixtures.zip(parts)));
    }
}
