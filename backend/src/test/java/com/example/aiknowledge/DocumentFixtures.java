package com.example.aiknowledge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;

final class DocumentFixtures {
    static byte[] pdf(int pages,boolean encrypted) throws IOException {
        try(var pdf=new PDDocument(); var output=new ByteArrayOutputStream()) {
            for(int i=0;i<pages;i++) pdf.addPage(new PDPage());
            if(encrypted) pdf.protect(new StandardProtectionPolicy("test-owner","test-user",new AccessPermission()));
            pdf.save(output); return output.toByteArray();
        }
    }
    static Map<String,String> docxParts() {
        var parts=new LinkedHashMap<String,String>();
        parts.put("[Content_Types].xml","""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>""");
        parts.put("_rels/.rels","""
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>""");
        parts.put("word/document.xml","""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>第二十课：原文件保持不变。</w:t></w:r></w:p></w:body></w:document>""");
        return parts;
    }
    static byte[] zip(Map<String,String> parts) throws IOException {
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)) {
            for(var part:parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    static byte[] docx() throws IOException { return zip(docxParts()); }
}
