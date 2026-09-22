package com.example.aiknowledge.service;

import java.util.*;
import com.example.aiknowledge.exception.DocumentException;

/** 字符分块教学实现；偏移基于提取后的 Java String，不是原文件页码或 token。 */
public final class TextChunker {
    private TextChunker() {}
    public record Chunk(int index,int startOffset,int endOffset,int overlap,String text) {}
    public static void validate(int size,int overlap) {
        if(size<200 || size>2000 || overlap<0 || overlap>200 || overlap*2>=size)
            throw new DocumentException(DocumentException.Kind.INVALID_INPUT,
                "块大小需为 200–2000 字符；重叠需为 0–200 字符，且小于块大小的一半。");
    }
    public static List<Chunk> split(String text,int size,int overlap) {
        validate(size,overlap);
        if(text.length()>DocumentTextExtractor.MAX_CHARACTERS)
            throw new DocumentException(DocumentException.Kind.INVALID_INPUT,"分块预览最多处理 40000 字符。");
        if(text.isBlank()) return List.of();
        var chunks=new ArrayList<Chunk>();
        int start=0,previousEnd=0;
        while(start<text.length()) {
            int end=Math.min(text.length(),start+size);
            if(end<text.length()) {
                end=safeEnd(text,end);
                // 只在窗口后 40% 内寻找边界，避免短段落造成几乎不前进的块。
                int minimum=start+size*3/5;
                for(String separator:List.of("\n\n","\n","。","！","？",". ","! ","? ","；","; "," ")) {
                    int at=text.lastIndexOf(separator,end-separator.length());
                    if(at>=start && at+separator.length()>=minimum) {
                        end=at+separator.length(); break;
                    }
                }
            }
            chunks.add(new Chunk(chunks.size(),start,end,Math.max(0,previousEnd-start),text.substring(start,end)));
            if(end==text.length()) break;
            previousEnd=end;
            start=end-overlap;
            // 若目标重叠从 emoji 的后半个代理字符开始，将起点右移一位。
            if(start>0 && Character.isLowSurrogate(text.charAt(start)) && Character.isHighSurrogate(text.charAt(start-1))) start++;
        }
        return List.copyOf(chunks);
    }
    private static int safeEnd(String text,int end) {
        if(end>0 && Character.isHighSurrogate(text.charAt(end-1)) && Character.isLowSurrogate(text.charAt(end))) return end-1;
        return end;
    }
}
