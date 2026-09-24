package com.example.aiknowledge.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.w3c.dom.*;
import com.example.aiknowledge.exception.DocumentException;

/** 只读正文预览；不保存索引、不调用模型。 */
public final class DocumentTextExtractor {
    public static final int MAX_CHARACTERS=40000, MAX_PDF_PAGES=20;
    private DocumentTextExtractor() {}
    public record Text(String content,boolean truncated,String note) {}
    public static Text extract(String name,byte[] bytes) {
        return extract(name,bytes,MAX_CHARACTERS,MAX_PDF_PAGES,false);
    }
    public static Text forIndex(String name,byte[] bytes) {
        return extract(name,bytes,10000,50,true);
    }
    private static Text extract(String name,byte[] bytes,int characters,int pages,boolean strict) {
        String type=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(bytes.length==0 || bytes.length>DocumentService.MAX_BYTES)
            throw new DocumentException(DocumentException.Kind.INVALID_INPUT,"文件大小不符合正文预览限制。");
        var output=new PreviewWriter(characters);
        boolean pageLimited=false;
        try {
            if(type.equals("doc")) {
                output.write(LegacyWordReader.read(bytes));
            } else if(type.equals("docx")) {
                // 复用 ZIP 限额、CRC、关系与安全 XML 校验，避免另建不受保护的解压入口。
                var document=DocumentFormatValidator.docx(bytes);
                var body=(Element)document.getElementsByTagNameNS(document.getNamespaceURI(),"body").item(0);
                word(body,document.getNamespaceURI(),output);
            } else {
                DocumentFormatValidator.validate(type,bytes);
                if(type.equals("pdf")) {
                    try(var pdf=Loader.loadPDF(bytes)) {
                        pageLimited=pdf.getNumberOfPages()>pages;
                        if(strict && pageLimited) throw new DocumentException(DocumentException.Kind.INVALID_INPUT,"本课索引仅支持不超过 50 页的 PDF，请拆分文件后重试。");
                        var stripper=new PDFTextStripper();
                        stripper.setSortByPosition(true);
                        stripper.setStartPage(1); stripper.setEndPage(pages);
                        stripper.writeText(pdf,output);
                    }
                } else if(type.equals("rtf")) {
                    var kit=new javax.swing.text.rtf.RTFEditorKit();
                    var document=kit.createDefaultDocument();
                    kit.read(new ByteArrayInputStream(bytes),document,0);
                    output.write(document.getText(0,document.getLength()));
                } else if(type.equals("html") || type.equals("htm")) {
                    html(new String(bytes,StandardCharsets.UTF_8),output);
                } else output.write(new String(bytes,StandardCharsets.UTF_8));
            }
        } catch(PreviewLimit reached) {
            if(strict) throw new DocumentException(DocumentException.Kind.INVALID_INPUT,"正文超过索引的 10000 字符上限，请拆分文件；未保存部分索引。");
            output.truncated=true;
        }
        catch(DocumentException error) { throw error; }
        catch(Exception error) {
            throw new DocumentException(DocumentException.Kind.INVALID_INPUT,"无法提取正文，请重新导出文件后再试。");
        }
        String content=output.value.toString().replace("\r\n","\n").replace('\r','\n');
        if(content.startsWith("\uFEFF")) content=content.substring(1);
        // 字符预算按 UTF-16 计数，截断时不保留半个代理对。
        if(!content.isEmpty() && Character.isHighSurrogate(content.charAt(content.length()-1))) content=content.substring(0,content.length()-1);
        String note=switch(type) {
            case "pdf" -> "仅提取 PDF 文本层，不识别扫描图片；多栏和表格的阅读顺序可能不准确。";
            case "docx" -> "提取主文档段落和表格中的文字；不含页眉页脚、图片、批注、文本框、自动编号或完整排版。";
            case "doc" -> "提取 Word 97–2003 DOC 主文档文字；不保留原排版、图片、页眉页脚或嵌入对象，不执行字段或宏。";
            case "html","htm" -> "仅提取 HTML 文字，不执行脚本、不加载外部链接或图片。";
            case "rtf" -> "仅提取 RTF 文字，不保留样式、图片或嵌入对象。";
            case "csv","tsv" -> "按 UTF-8 读取表格文本，保留分隔符；不会执行单元格公式。";
            case "json" -> "按 UTF-8 读取 JSON，保留字段名与结构。";
            default -> "按 UTF-8 读取文字，Markdown 保留原始标记。";
        };
        if(pageLimited) note+=" 本次只读取前 20 页。";
        if(output.truncated) note+=" 正文超过 40000 字符，预览已截断。";
        if(content.isBlank()) { content=""; note+=" 当前预览范围未提取到文字，不表示原文件没有内容。"; }
        return new Text(content,pageLimited || output.truncated,note);
    }
    private static void html(String source,Writer output) throws IOException {
        try {
            new javax.swing.text.html.parser.ParserDelegator().parse(new StringReader(source),
                new javax.swing.text.html.HTMLEditorKit.ParserCallback() {
                    int hidden;
                    private void write(String value) {
                        try { output.write(value); } catch(IOException error) { throw new UncheckedIOException(error); }
                    }
                    @Override public void handleStartTag(javax.swing.text.html.HTML.Tag tag,javax.swing.text.MutableAttributeSet attributes,int position) {
                        if(tag==javax.swing.text.html.HTML.Tag.SCRIPT || tag==javax.swing.text.html.HTML.Tag.STYLE) hidden++;
                    }
                    @Override public void handleEndTag(javax.swing.text.html.HTML.Tag tag,int position) {
                        if(tag==javax.swing.text.html.HTML.Tag.SCRIPT || tag==javax.swing.text.html.HTML.Tag.STYLE) hidden=Math.max(0,hidden-1);
                        else if(hidden==0 && tag.isBlock()) write("\n");
                    }
                    @Override public void handleText(char[] text,int position) { if(hidden==0) write(new String(text)); }
                    @Override public void handleSimpleTag(javax.swing.text.html.HTML.Tag tag,javax.swing.text.MutableAttributeSet attributes,int position) {
                        if(hidden==0 && tag==javax.swing.text.html.HTML.Tag.BR) write("\n");
                    }
                },true);
        } catch(UncheckedIOException error) { throw error.getCause(); }
    }
    private static void word(Element element,String namespace,Writer output) throws IOException {
        if(!namespace.equals(element.getNamespaceURI())) return;
        String tag=element.getLocalName();
        if(java.util.Set.of("del","moveFrom","drawing","pict","txbxContent").contains(tag)) return;
        switch(tag) {
            case "t" -> { output.write(element.getTextContent()); return; }
            case "tab" -> { output.write("\t"); return; }
            case "br","cr" -> { output.write("\n"); return; }
        }
        for(Node node=element.getFirstChild();node!=null;node=node.getNextSibling())
            if(node instanceof Element child) word(child,namespace,output);
        if(tag.equals("p") || tag.equals("tr")) output.write("\n");
        if(tag.equals("tc")) output.write("\t");
    }
    private static class PreviewLimit extends IOException {}
    private static class PreviewWriter extends Writer {
        final StringBuilder value=new StringBuilder();
        final int limit;
        PreviewWriter(int limit) { this.limit=limit; }
        boolean truncated;
        @Override public void write(char[] chars,int offset,int length) throws IOException {
            int available=limit-value.length();
            value.append(chars,offset,Math.min(available,length));
            if(length>available) throw new PreviewLimit();
        }
        @Override public void flush() {}
        @Override public void close() {}
    }
}
