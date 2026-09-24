package com.example.aiknowledge.service;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import com.example.aiknowledge.exception.DocumentException;

/** 校验上传格式，不提取正文，也不改写文件。 */
public final class DocumentFormatValidator {
    public static final Set<String> TYPES=Set.of("txt","md","pdf","doc","docx","csv","tsv","json","html","htm","rtf");
    private DocumentFormatValidator() {}
    private static final int MAX_ENTRIES=128, MAX_ENTRY_BYTES=4*1024*1024, MAX_EXPANDED_BYTES=8*1024*1024;
    private static final String CONTENT_TYPES="http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String RELATIONSHIPS="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String WORD="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String STRICT_WORD="http://purl.oclc.org/ooxml/wordprocessingml/main";
    private static final String MAIN_TYPE="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    public static void validate(String type,byte[] bytes) {
        switch(type) {
            case "txt","md","csv","tsv","html","htm" -> text(bytes);
            case "json" -> {
                text(bytes);
                try { tools.jackson.databind.json.JsonMapper.builder()
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readTree(bytes); }
                catch(Exception error) { throw invalid("JSON 结构无效，请检查后上传。"); }
            }
            case "rtf" -> {
                if(!new String(bytes,StandardCharsets.ISO_8859_1).startsWith("{\\rtf"))
                    throw invalid("文件内容不是 RTF，请重新导出。");
            }
            case "pdf" -> pdf(bytes);
            case "docx" -> docx(bytes);
            case "doc" -> LegacyWordReader.read(bytes);
            default -> throw invalid("支持 TXT、Markdown、PDF、DOC、DOCX、CSV、TSV、JSON、HTML 和 RTF。");
        }
    }
    private static DocumentException invalid(String message) {
        return new DocumentException(DocumentException.Kind.INVALID_INPUT,message);
    }
    private static void text(byte[] bytes) {
        try {
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(value.indexOf('\0')>=0) throw new CharacterCodingException();
        } catch(CharacterCodingException error) { throw invalid("TXT / Markdown 必须为 UTF-8 文本。"); }
    }
    private static void pdf(byte[] bytes) {
        if(bytes.length<8 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))
            throw invalid("文件内容不是 PDF，请勿仅修改后缀。");
        try(var document=Loader.loadPDF(bytes)) {
            if(document.isEncrypted()) throw invalid("本课暂不支持加密 PDF，请先导出为未加密文件。");
            int pages=0;
            for(var page:document.getPages()) {
                if(++pages>500) throw invalid("本课 PDF 最多支持 500 页。");
            }
            if(pages==0) throw invalid("PDF 必须包含至少一页。");
        } catch(org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException error) {
            throw invalid("本课暂不支持加密 PDF，请先导出为未加密文件。");
        } catch(IOException | IllegalArgumentException error) {
            throw invalid("PDF 结构无法读取，请重新导出文件后上传。");
        }
    }
    static Element docx(byte[] bytes) {
        Path temporary=null;
        try {
            // ZipFile 检查 ZIP 中央目录；这里只暂存压缩包，不解压到用户路径。
            temporary=Files.createTempFile("course-docx-check-",".zip");
            Files.write(temporary,bytes);
            Map<String,byte[]> parts=new HashMap<>();
            Set<String> names=new HashSet<>();
            int count=0,total=0;
            try(var zip=new ZipFile(temporary.toFile(),StandardCharsets.UTF_8)) {
                var entries=zip.entries();
                while(entries.hasMoreElements()) {
                    var entry=entries.nextElement();
                    String name=entry.getName();
                    if(++count>MAX_ENTRIES) throw invalid("DOCX 内容项过多，本课最多支持 128 项。");
                    if(name.startsWith("/") || name.contains("\\") || name.contains(":")
                            || Arrays.asList(name.split("/")).contains("..") || !names.add(name))
                        throw invalid("DOCX 包含不合法或重复的内部路径。");
                    if(name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin"))
                        throw invalid("本课不支持含宏的 Word 文件。");
                    if(entry.isDirectory()) continue;
                    byte[] expanded;
                    try(var input=zip.getInputStream(entry)) { expanded=input.readNBytes(MAX_ENTRY_BYTES+1); }
                    total+=expanded.length;
                    if(expanded.length>MAX_ENTRY_BYTES || total>MAX_EXPANDED_BYTES)
                        throw invalid("DOCX 解压后的内容过大：单项最多 4 MB、合计最多 8 MB。");
                    var crc=new CRC32(); crc.update(expanded);
                    if(entry.getSize()!=expanded.length || entry.getCrc()!=crc.getValue())
                        throw invalid("DOCX 压缩包已损坏，请重新保存后上传。");
                    if(Set.of("[Content_Types].xml","_rels/.rels","word/document.xml").contains(name)) parts.put(name,expanded);
                }
            }
            Element types=xml(parts.get("[Content_Types].xml"));
            Element relations=xml(parts.get("_rels/.rels"));
            Element document=xml(parts.get("word/document.xml"));
            if(!root(types,CONTENT_TYPES,"Types") || !root(relations,RELATIONSHIPS,"Relationships")
                    || !(root(document,WORD,"document") || root(document,STRICT_WORD,"document")))
                throw invalid("DOCX 缺少有效的 Word 文档结构。");
            boolean main=false,linked=false;
            var overrides=types.getElementsByTagNameNS(CONTENT_TYPES,"Override");
            for(int i=0;i<overrides.getLength();i++) {
                var item=(Element)overrides.item(i);
                if(item.getAttribute("ContentType").toLowerCase(Locale.ROOT).contains("macroenabled"))
                    throw invalid("本课不支持含宏的 Word 文件。");
                if(item.getAttribute("PartName").equals("/word/document.xml") && item.getAttribute("ContentType").equals(MAIN_TYPE)) main=true;
            }
            var links=relations.getElementsByTagNameNS(RELATIONSHIPS,"Relationship");
            for(int i=0;i<links.getLength();i++) {
                var item=(Element)links.item(i);
                String kind=item.getAttribute("Type"),target=item.getAttribute("Target"),mode=item.getAttribute("TargetMode");
                if((kind.equals("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument")
                        || kind.equals("http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument"))
                        && (mode.isEmpty() || mode.equals("Internal")) && (target.equals("word/document.xml") || target.equals("/word/document.xml"))) linked=true;
            }
            if(!main || !linked || document.getElementsByTagNameNS(document.getNamespaceURI(),"body").getLength()!=1)
                throw invalid("DOCX 主文档声明、关系或正文结构不完整。");
            return document;
        } catch(DocumentException error) { throw error; }
        catch(Exception error) { throw invalid("DOCX 结构无法读取，请用 Word 重新保存为 .docx 后上传。"); }
        finally {
            if(temporary!=null) try { Files.deleteIfExists(temporary); }
            catch(IOException error) { org.slf4j.LoggerFactory.getLogger(DocumentFormatValidator.class).warn("Temporary DOCX validation file cleanup failed"); }
        }
    }
    private static boolean root(Element element,String namespace,String name) {
        return namespace.equals(element.getNamespaceURI()) && name.equals(element.getLocalName());
    }
    private static Element xml(byte[] bytes) throws Exception {
        if(bytes==null) throw invalid("DOCX 缺少必要的内部 XML 文件。");
        var factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth",128);
        factory.setXIncludeAware(false);
        var builder=factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException e) throws SAXException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
        });
        return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    }
}
