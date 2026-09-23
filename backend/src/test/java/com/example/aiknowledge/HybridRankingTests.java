package com.example.aiknowledge;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.example.aiknowledge.service.*;
import com.example.aiknowledge.exception.ChatException;

class HybridRankingTests {
    KnowledgeBaseRagService.Hit hit(long doc,int chunk,String text,double score) {
        return new KnowledgeBaseRagService.Hit(0,doc,"file",chunk,0,text.length(),text,score,null,false,"");
    }
    @Test void termsAreBoundedDeduplicatedAndCaseInsensitive() {
        assertEquals(List.of("utf-8"),HybridRanking.keywords("hybrid",List.of(" UTF-8 ","utf-8")));
        for(var terms:List.of(List.<String>of(),List.of(" "),List.of("x".repeat(41)),List.of("a","b","c","d","e","f")))
            assertEquals(400,assertThrows(ChatException.class,()->HybridRanking.keywords("hybrid",terms)).status());
        assertThrows(ChatException.class,()->HybridRanking.keywords("bad",List.of()));
    }
    @Test void fusionUsesRanksRatherThanAddingIncompatibleScores() {
        var a=hit(1,0,"一般说明",.99); var b=hit(2,0,"UTF-8",.1);
        var fused=HybridRanking.fuse(List.of(a,b),List.of(a,b),List.of("utf-8"));
        assertEquals(2,fused.get(0).documentId());
        assertEquals(1.0/62+1.0/61,fused.get(0).score(),1e-12);
        assertEquals(1.0/61,fused.get(1).score(),1e-12);
    }
    @Test void lexicalRouteCanRecallChunksOutsideVectorCandidatesAndNoMatchAddsNothing() {
        var a=hit(1,0,"语义候选",.9); var b=hit(2,4,"ZX-904",0);
        assertEquals(2,HybridRanking.fuse(List.of(a),List.of(a,b),List.of("zx-904")).size());
        assertEquals(1,HybridRanking.fuse(List.of(a),List.of(a,b),List.of("不存在")).size());
    }
    @Test void distinctTermCoverageNotRepetitionDeterminesKeywordRankAndTiesAreStable() {
        var a=hit(1,0,"a a a",0); var b=hit(2,0,"a b",0);
        assertEquals(2,HybridRanking.fuse(List.of(),List.of(a,b),List.of("a","b")).get(0).documentId());
        assertEquals(1,HybridRanking.fuse(List.of(),List.of(b,a),List.of("a")).get(0).documentId());
    }
    @Test void lexicalCandidateDepthIsBounded() {
        var all=new ArrayList<KnowledgeBaseRagService.Hit>();
        for(int i=20;i>0;i--) all.add(hit(i,0,"term",0));
        var fused=HybridRanking.fuse(List.of(),all,List.of("term"));
        assertEquals(15,fused.size()); assertEquals(15,fused.get(14).documentId());
    }
}
