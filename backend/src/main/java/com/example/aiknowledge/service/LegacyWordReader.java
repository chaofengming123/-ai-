package com.example.aiknowledge.service;

import java.io.*;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.*;
import com.example.aiknowledge.exception.DocumentException;

/** Word 97–2003 binary documents; never execute macros, fields or embedded objects. */
final class LegacyWordReader {
    private LegacyWordReader() {}
    private static final Semaphore CAPACITY=new Semaphore(2);
    private static final byte[] SIGNATURE={(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1};
    static String read(byte[] bytes) {
        if(bytes.length<SIGNATURE.length || bytes.length>DocumentService.MAX_BYTES)
            throw invalid("DOC 文件大小不合法。");
        for(int i=0;i<SIGNATURE.length;i++) if(bytes[i]!=SIGNATURE[i])
            throw invalid("文件不是 Word 97–2003 DOC，请使用 Word 另存为 .doc 或 .docx，不要仅修改后缀。");
        if(!CAPACITY.tryAcquire()) throw new DocumentException(DocumentException.Kind.BUSY,"当前 Word 文档处理较多，请稍后重试。");
        try(var filesystem=new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            inspect(filesystem.getRoot(),0,new int[]{0});
            if(!filesystem.getRoot().hasEntry("WordDocument")) throw invalid("DOC 缺少 Word 主文档结构。");
            try(var document=new HWPFDocument(filesystem)) {
                if(document.getFileInformationBlock().getFibBase().isFEncrypted())
                    throw invalid("暂不支持加密 DOC，请先解除密码保护再上传。");
                return Range.stripFields(document.getRange().text()).replace('\u0007','\t').replace('\u000b','\n');
            }
        } catch(DocumentException error) { throw error; }
        catch(org.apache.poi.EncryptedDocumentException error) { throw invalid("暂不支持加密 DOC，请先解除密码保护再上传。"); }
        catch(Exception error) { throw invalid("DOC 结构无法读取，支持 Word 97–2003 格式；请用 Word 重新另存后上传。"); }
        finally { CAPACITY.release(); }
    }
    private static void inspect(DirectoryEntry directory,int depth,int[] count) {
        if(depth>16) throw invalid("DOC 内部结构过深。");
        for(Entry entry:directory) {
            if(++count[0]>128) throw invalid("DOC 内部内容项过多。");
            String name=entry.getName().toUpperCase(Locale.ROOT);
            if(Set.of("MACROS","VBA","_VBA_PROJECT","_VBA_PROJECT_CUR").contains(name))
                throw invalid("暂不支持含宏的 DOC，请移除宏后重新保存。");
            if(entry instanceof DirectoryEntry child) inspect(child,depth+1,count);
            if(entry instanceof DocumentEntry stream && stream.getSize()>DocumentService.MAX_BYTES)
                throw invalid("DOC 内部内容过大。");
        }
    }
    private static DocumentException invalid(String message) {
        return new DocumentException(DocumentException.Kind.INVALID_INPUT,message);
    }
}
