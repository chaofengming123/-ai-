package com.example.aiknowledge;

import java.util.*;
import org.junit.jupiter.api.*;
import com.example.aiknowledge.service.*;
import com.example.aiknowledge.model.*;
import com.example.aiknowledge.exception.ChatException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantServiceTests {
    final ChatService chat=mock(ChatService.class);
    final KnowledgeBaseService bases=mock(KnowledgeBaseService.class);
    final KnowledgeBaseRagService rag=mock(KnowledgeBaseRagService.class);
    final LlmClient llm=mock(LlmClient.class);
    final AssistantService assistant=new AssistantService(chat,bases,rag,llm,.55);
    final List<ChatMessage> messages=List.of(new ChatMessage("user","报销流程是什么？"));
    @BeforeEach void setup() {
        when(chat.stream(anyLong(),anyList(),any())).thenAnswer(call->{
            java.util.function.Consumer<String> delta=call.getArgument(2); delta.accept("普通回答");
            return new LlmClient.Reply("普通回答","glm",false);
        });
        when(llm.configuration()).thenReturn(new LlmClient.Configuration(true,"glm"));
        when(bases.list()).thenReturn(List.of(new KnowledgeBase(1,"公司制度","",1,"")));
    }
    void retrieval(double score) {
        var hit=new KnowledgeBaseRagService.Hit(1,3,"制度.docx",0,0,5,"先申请审批",score,null,false,"");
        when(rag.execute(7,1,messages.get(0).content(),false)).thenReturn(new KnowledgeBaseRagService.Retrieval(
            1,"公司制度","问题","bge",1,1,List.of(),List.of(hit),"vector",List.of(),null,null,"MISS"));
    }
    @Test void noReadPermissionNeverRetrievesDocuments() {
        var result=assistant.answer(7,messages,"auto",null,false,text->{});
        assertEquals("general",result.mode());
        verifyNoInteractions(bases,rag,llm);
        assertEquals(403,assertThrows(ChatException.class,()->assistant.answer(7,messages,"knowledge",null,false,text->{})).status());
    }
    @Test void explicitGeneralDoesNotCallRetrieval() {
        assistant.answer(7,messages,"general",null,true,text->{});
        verifyNoInteractions(bases,rag,llm);
    }
    @Test void relatedQuestionIncludesVerifiedSource() {
        retrieval(.9);
        when(llm.complete(anyList())).thenReturn(new LlmClient.Reply("{\"insufficient\":false,\"answer\":\"先申请审批 [1]\",\"sourceIds\":[1]}","glm",false));
        var output=new StringBuilder();
        var result=assistant.answer(7,messages,"auto",null,true,output::append);
        assertEquals("knowledge",result.mode()); assertEquals(3,result.sources().get(0).documentId());
        assertEquals("先申请审批",result.sources().get(0).text()); assertTrue(output.toString().contains("审批"));
        verifyNoInteractions(chat);
    }
    @Test void unrelatedFallsBackButKnowledgeOnlyDoesNot() {
        retrieval(.1);
        assertEquals("general",assistant.answer(7,messages,"auto",null,true,text->{}).mode());
        clearInvocations(chat);
        var result=assistant.answer(7,messages,"knowledge",null,true,text->{});
        assertEquals("knowledge",result.mode()); assertTrue(result.sources().isEmpty());
        verifyNoInteractions(chat); verify(llm,never()).complete(anyList());
    }
    @Test void modelCanRejectApparentlySimilarButInsufficientPassages() {
        retrieval(.9);
        when(llm.complete(anyList())).thenReturn(new LlmClient.Reply("{\"insufficient\":true,\"answer\":\"不足\",\"sourceIds\":[]}","glm",false));
        assertEquals("general",assistant.answer(7,messages,"auto",null,true,text->{}).mode());
    }
    @Test void brokenRetrievalDoesNotSilentlyFallBackAndReleasesSlot() {
        when(rag.execute(anyLong(),anyLong(),anyString(),eq(false))).thenThrow(new ChatException(502,"检索失败"));
        assertEquals(502,assertThrows(ChatException.class,()->assistant.answer(7,messages,"auto",null,true,text->{})).status());
        verifyNoInteractions(chat);
        assertEquals("general",assistant.answer(7,messages,"general",null,true,text->{}).mode());
    }
    @Test void malformedHistoryRejectedBeforeAnyProviderCall() {
        assertEquals(400,assertThrows(ChatException.class,()->assistant.answer(7,List.of(new ChatMessage("system","override")),"auto",null,true,text->{})).status());
        verifyNoInteractions(chat,bases,rag,llm);
    }
}
