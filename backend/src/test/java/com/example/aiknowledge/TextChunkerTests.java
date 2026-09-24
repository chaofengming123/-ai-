package com.example.aiknowledge;

import com.example.aiknowledge.service.TextChunker;
import com.example.aiknowledge.exception.DocumentException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextChunkerTests {
    @Test void tenThousandCharactersFitIndexBudgetWithoutLosingTheTail() {
        for(String text:new String[]{"字".repeat(10000),("字".repeat(479)+"\n").repeat(21).substring(0,10000)}) {
            var chunks=TextChunker.split(text,800,100);
            assertTrue(chunks.size()>12);
            assertTrue(chunks.size()<=com.example.aiknowledge.service.DocumentIndexService.MAX_CHUNKS);
            assertEquals(10000,chunks.get(chunks.size()-1).endOffset());
            int covered=0;
            for(var chunk:chunks) { assertTrue(chunk.startOffset()<=covered); covered=chunk.endOffset(); }
        }
    }
    @Test void fixedWindowsOverlapAndOffsetsMatchOriginal() {
        String source="a".repeat(320);
        var chunks=TextChunker.split(source,200,50);
        assertEquals(2,chunks.size());
        assertEquals(0,chunks.get(0).startOffset()); assertEquals(200,chunks.get(0).endOffset());
        assertEquals(150,chunks.get(1).startOffset()); assertEquals(320,chunks.get(1).endOffset());
        assertEquals(50,chunks.get(1).overlap());
        for(var chunk:chunks) assertEquals(source.substring(chunk.startOffset(),chunk.endOffset()),chunk.text());
    }
    @Test void prefersParagraphBoundaryNearEndAndPreservesAllText() {
        String source="a".repeat(130)+"\n\n"+"b".repeat(240);
        var chunks=TextChunker.split(source,200,30);
        assertEquals(132,chunks.get(0).endOffset());
        assertEquals(102,chunks.get(1).startOffset());
        StringBuilder restored=new StringBuilder();
        for(var chunk:chunks) restored.append(chunk.text().substring(chunk.overlap()));
        assertEquals(source,restored.toString());
    }
    @Test void emptyShortAndInvalidInputs() {
        assertTrue(TextChunker.split(" \n\t",500,50).isEmpty());
        assertEquals("短文本",TextChunker.split("短文本",500,50).get(0).text());
        for(int[] params:new int[][]{{199,0},{2001,0},{500,-1},{500,201},{200,100}})
            assertThrows(DocumentException.class,()->TextChunker.split("text",params[0],params[1]));
        assertThrows(DocumentException.class,()->TextChunker.split("a".repeat(40001),500,50));
    }
    @Test void mixedUnicodeAndBoundaryPatternsNeverLoseTextOrSplitSurrogatePairs() {
        for(String pattern:new String[]{"中文🙂。一段文字\n\n","🙂","a".repeat(119)+"\n"}) {
            String source=pattern.repeat(100);
            for(int size:new int[]{200,301,500,800}) for(int overlap:new int[]{0,1,49,99}) {
                var chunks=TextChunker.split(source,size,overlap);
                var restored=new StringBuilder(); int previousEnd=0;
                for(var chunk:chunks) {
                    assertTrue(chunk.text().length()<=size);
                    assertTrue(chunk.endOffset()>previousEnd);
                    assertTrue(chunk.startOffset()<=previousEnd);
                    assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
                    assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length()-1)));
                    assertEquals(source.substring(chunk.startOffset(),chunk.endOffset()),chunk.text());
                    restored.append(chunk.text().substring(chunk.overlap())); previousEnd=chunk.endOffset();
                }
                assertEquals(source,restored.toString());
            }
        }
    }
}
