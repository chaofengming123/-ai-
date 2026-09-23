package com.example.aiknowledge;

import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import com.example.aiknowledge.service.*;
import com.example.aiknowledge.mapper.*;
import com.example.aiknowledge.model.*;
import com.example.aiknowledge.exception.ChatException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeBaseRagTests {
    final KnowledgeBaseService bases=mock(KnowledgeBaseService.class);
    final DocumentMapper documents=mock(DocumentMapper.class);
    final DocumentIndexMapper indexes=mock(DocumentIndexMapper.class);
    final DocumentSearchService search=mock(DocumentSearchService.class);
    final EmbeddingClient embedding=mock(EmbeddingClient.class);
    final LlmClient llm=mock(LlmClient.class);
    final RerankClient reranker=mock(RerankClient.class);
    final KnowledgeBaseRagService rag=new KnowledgeBaseRagService(bases,documents,indexes,search,embedding,llm,reranker);
    final LocalDateTime time=LocalDateTime.of(2026,9,23,0,0);
    final double[] vector={1,0};
    DocumentInfo document(long id) { return new DocumentInfo(id,1,"doc"+id+".txt","txt",10,"UPLOADED",time); }
    DocumentIndex index(long id,String space,String collection) { return new DocumentIndex(id,"READY","a",null,collection,"model",space,"sha",3,2,800,"note",time,time); }
    DocumentSearchService.Result result(long id,DocumentSearchService.Hit... hits) {
        return new DocumentSearchService.Result(id,1,"doc"+id+".txt","q","model",time,false,"note",List.of(hits));
    }
    DocumentSearchService.Hit hit(int id,String text,double score) { return new DocumentSearchService.Hit(id,0,text.length(),text,score); }
    @BeforeEach void setup() {
        when(reranker.configuration()).thenReturn(new RerankClient.Configuration(true,"bge-reranker"));
        when(bases.get(1)).thenReturn(new KnowledgeBase(1,"知识库","",0,""));
        when(embedding.spaceId()).thenReturn("space"); when(embedding.configuration()).thenReturn(new EmbeddingClient.Configuration(true,"model"));
        when(embedding.embed(anyList())).thenReturn(List.of(vector));
        when(llm.configuration()).thenReturn(new LlmClient.Configuration(true,"glm"));
    }
    @Test void hybridRecallsMissingChunkAndRejectsScanFailureBeforeGeneration() {
        when(documents.list(1)).thenReturn(List.of(document(1)));
        when(indexes.find(1)).thenReturn(index(1,"space","c1"));
        when(search.searchWithVector(eq(1L),anyString(),any())).thenReturn(result(1,hit(0,"一般",.9)));
        when(search.scanForKeywords(1,"q")).thenReturn(result(1,hit(0,"一般",0),hit(1,"ZX-904",0)));
        var result=(KnowledgeBaseRagService.Retrieval)rag.execute(9,1,"q",false,"hybrid",List.of("ZX-904"));
        assertEquals(List.of("一般","ZX-904"),result.matches().stream().map(KnowledgeBaseRagService.Hit::text).toList());
        verify(embedding,times(1)).embed(List.of("q"));
        when(search.scanForKeywords(1,"q")).thenThrow(new ChatException(502,"不完整"));
        assertThrows(ChatException.class,()->rag.execute(9,1,"q",true,"hybrid",List.of("ZX-904")));
        verify(llm,never()).complete(anyList());
    }
    void prepareRerank() {
        when(documents.list(1)).thenReturn(List.of(document(1),document(2),document(3)));
        for(long id=1;id<=3;id++) {
            when(indexes.find(id)).thenReturn(index(id,"space","c"+id));
            when(search.searchWithVector(eq(id),anyString(),any())).thenReturn(result(id,
                hit(0,"文档"+id+"甲",1-id*.1),hit(1,"文档"+id+"乙",1-id*.1-.01),hit(2,"文档"+id+"丙",1-id*.1-.02)));
        }
    }
    @Test void rerankCapsPoolRenumbersSourcesAndKeepsSameRequestBaseline() {
        prepareRerank();
        when(reranker.select(anyString(),anyList())).thenReturn(List.of(5,3,0));
        when(llm.complete(anyList())).thenReturn(
            new LlmClient.Reply("{\"insufficient\":false,\"answer\":\"答\",\"sourceIds\":[1]}","glm",false));
        var answer=(KnowledgeBaseRagService.Answer)rag.execute(9,1,"q",true,"vector",List.of(),true);
        assertEquals(6,answer.retrieval().rerank().candidateCount()); assertTrue(answer.retrieval().rerank().applied());
        assertEquals(List.of(1L,1L,1L),answer.retrieval().rerank().before().stream().map(KnowledgeBaseRagService.Hit::documentId).toList());
        assertEquals("文档2丙",answer.sources().get(0).text()); assertEquals(1,answer.sources().get(0).sourceId());
        assertEquals(.78,answer.sources().get(0).score(),1e-12); verify(llm,times(1)).complete(anyList()); verify(reranker).select(eq("q"),argThat(texts->texts.size()==6));
        verify(embedding,times(1)).embed(List.of("q"));
    }
    @Test void changedIndexDuringRerankRejectsResultAndSkipsAnswerGeneration() {
        prepareRerank();
        when(reranker.select(anyString(),anyList())).thenAnswer(call->{
            when(indexes.find(1)).thenReturn(index(1,"space","new"));
            return List.of(0,1,2);
        });
        assertEquals(409,assertThrows(ChatException.class,()->rag.execute(9,1,"q",true,"vector",List.of(),true)).status());
        verify(llm,never()).complete(anyList());
    }
    @Test void rerankRequiresIndependentKeyAndWorksWithoutGlmAndFailureReleasesSlot() {
        when(reranker.configuration()).thenReturn(new RerankClient.Configuration(false,"bge"));
        assertEquals(503,assertThrows(ChatException.class,()->rag.execute(9,1,"q",false,"vector",List.of(),true)).status());
        verify(embedding,never()).embed(anyList());
        when(reranker.configuration()).thenReturn(new RerankClient.Configuration(true,"bge"));
        when(llm.configuration()).thenReturn(new LlmClient.Configuration(false,"glm"));
        prepareRerank();
        when(reranker.select(anyString(),anyList())).thenThrow(new ChatException(502,"bad"));
        assertEquals(502,assertThrows(ChatException.class,()->rag.execute(9,1,"q",false,"vector",List.of(),true)).status());
        doReturn(List.of(2,1,0)).when(reranker).select(anyString(),anyList());
        var result=(KnowledgeBaseRagService.Retrieval)rag.execute(9,1,"q",false,"vector",List.of(),true);
        assertTrue(result.rerank().applied()); assertEquals(3,result.matches().size());
        verify(llm,never()).complete(anyList());
    }
    @Test void oneEmbeddingRanksAcrossDocumentsDeduplicatesAndReportsSkippedFiles() {
        when(documents.list(1)).thenReturn(List.of(document(1),document(2),document(3),document(4)));
        when(indexes.find(1)).thenReturn(index(1,"space","c1")); when(indexes.find(2)).thenReturn(index(2,"space","c2"));
        when(indexes.find(4)).thenReturn(index(4,"other","c4"));
        when(search.searchWithVector(eq(1L),eq("q"),same(vector))).thenReturn(result(1,hit(0,"重复",.8),hit(1,"甲",.6)));
        when(search.searchWithVector(eq(2L),eq("q"),same(vector))).thenReturn(result(2,hit(0,"乙",.9),hit(1,"重复",.8),hit(2,"丙",.7)));
        var result=(KnowledgeBaseRagService.Retrieval)rag.execute(9,1,"q",false);
        assertEquals(List.of("乙","重复","丙"),result.matches().stream().map(KnowledgeBaseRagService.Hit::text).toList());
        assertEquals(List.of(1,2,3),result.matches().stream().map(KnowledgeBaseRagService.Hit::sourceId).toList());
        assertEquals(1,result.matches().get(1).documentId()); assertEquals(2,result.searchedDocuments()); assertEquals(2,result.skipped().size());
        verify(embedding,times(1)).embed(List.of("q")); verify(search,never()).searchWithVector(eq(3L),anyString(),any()); verifyNoInteractions(llm);
    }
    @Test void noEligibleDocumentsCallsNeitherModelAndReturnsInsufficient() {
        when(documents.list(1)).thenReturn(List.of(document(1)));
        var result=(KnowledgeBaseRagService.Answer)rag.execute(9,1,"q",true);
        assertTrue(result.insufficient()); assertEquals(1,result.retrieval().skipped().size());
        verify(embedding,never()).embed(anyList()); verify(llm,never()).complete(anyList());
    }
    @Test void tooManyEligibleIndexesAreRejectedBeforeEmbedding() {
        var docs=new ArrayList<DocumentInfo>();
        for(long id=1;id<=6;id++) { docs.add(document(id)); when(indexes.find(id)).thenReturn(index(id,"space","c"+id)); }
        when(documents.list(1)).thenReturn(docs);
        assertEquals(400,assertThrows(ChatException.class,()->rag.execute(9,1,"q",false)).status()); verify(embedding,never()).embed(anyList());
    }
    @Test void failedDocumentDoesNotGenerateAnAnswerFromPartialResults() {
        when(documents.list(1)).thenReturn(List.of(document(1),document(2)));
        when(indexes.find(1)).thenReturn(index(1,"space","c1")); when(indexes.find(2)).thenReturn(index(2,"space","c2"));
        when(search.searchWithVector(eq(1L),anyString(),any())).thenReturn(result(1,hit(0,"片段",.8)));
        when(search.searchWithVector(eq(2L),anyString(),any())).thenThrow(new ChatException(503,"存储故障"));
        assertThrows(ChatException.class,()->rag.execute(9,1,"q",true)); verify(llm,never()).complete(anyList());
    }
    @Test void answerSourcesMapToGlobalRankingAndVersionChangeRejectsOldAnswer() {
        when(documents.list(1)).thenReturn(List.of(document(1),document(2)));
        when(indexes.find(1)).thenReturn(index(1,"space","c1")); when(indexes.find(2)).thenReturn(index(2,"space","c2"));
        when(search.searchWithVector(eq(1L),anyString(),any())).thenReturn(result(1,hit(0,"甲",.7)));
        when(search.searchWithVector(eq(2L),anyString(),any())).thenReturn(result(2,hit(0,"乙",.9)));
        when(llm.complete(anyList())).thenReturn(new LlmClient.Reply("{\"insufficient\":false,\"answer\":\"答\",\"sourceIds\":[1]}","glm",false));
        var result=(KnowledgeBaseRagService.Answer)rag.execute(9,1,"q",true); assertEquals(2,result.sources().get(0).documentId());
        when(llm.complete(anyList())).thenAnswer(call->{
            when(indexes.find(2)).thenReturn(index(2,"space","new"));
            return new LlmClient.Reply("{\"insufficient\":false,\"answer\":\"答\",\"sourceIds\":[1]}","glm",false);
        });
        assertEquals(409,assertThrows(ChatException.class,()->rag.execute(9,1,"q",true)).status());
    }
}
