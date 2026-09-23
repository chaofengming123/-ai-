package com.example.aiknowledge;

import com.example.aiknowledge.mapper.*;
import com.example.aiknowledge.service.*;
import com.example.aiknowledge.exception.ChatException;
import io.minio.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="app.documents.backend=minio")
class DocumentIndexTests {
    static final String bucket="index27-test-"+UUID.randomUUID();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.minio.bucket",()->bucket); }
    @MockitoBean EmbeddingClient embedding;
    @MockitoBean LlmClient llm;
    @MockitoBean RerankClient reranker;
    @MockitoSpyBean QdrantClient qdrant;
    @Autowired DocumentService documents;
    @Autowired DocumentIndexService indexes;
    @Autowired DocumentSearchService searches;
    @Autowired DocumentIndexMapper rows;
    @Autowired DocumentMapper documentMapper;
    @Autowired KnowledgeBaseService bases;
    @Autowired UserService users;
    @Autowired LoginService login;
    @Autowired UserAccessMapper access;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    final List<Long> documentIds=new ArrayList<>();
    final JsonMapper json=JsonMapper.builder().build();
    long baseId,userId; String token;
    static MinioClient minio() { return MinioClient.builder().endpoint("http://127.0.0.1:9000")
        .credentials(System.getenv("MINIO_ROOT_USER"),System.getenv("MINIO_ROOT_PASSWORD")).build(); }
    @BeforeEach void setup() {
        when(reranker.configuration()).thenReturn(new RerankClient.Configuration(true,"BAAI/bge-reranker-v2-m3"));
        when(llm.configuration()).thenReturn(new LlmClient.Configuration(true,"glm-test"));
        when(llm.complete(anyList())).thenReturn(new LlmClient.Reply("{\"insufficient\":false,\"answer\":\"根据文档上传。\",\"sourceIds\":[1]}","glm-test",false));
        when(embedding.spaceId()).thenReturn("test-space");
        when(embedding.configuration()).thenReturn(new EmbeddingClient.Configuration(true,"test-model"));
        when(embedding.embed(anyList())).thenAnswer(call->((List<?>)call.getArgument(0)).stream().map(x->new double[]{1,0}).toList());
        String name="idx_"+UUID.randomUUID().toString().substring(0,8);
        userId=users.register(name,"test-password").id(); access.assignRole(userId,"EDITOR");
        token=login.login(name,"test-password").accessToken(); baseId=bases.create(name,"index test").id();
    }
    long upload(String text) {
        long id=documents.upload(baseId,new MockMultipartFile("file","source.txt","text/plain",text.getBytes(StandardCharsets.UTF_8))).id();
        documentIds.add(id); return id;
    }
    HttpResponse<String> request(String method,long id,String bearer) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/documents/"+id+"/index"))
            .method(method,HttpRequest.BodyPublishers.noBody());
        if(bearer!=null) builder.header("Authorization","Bearer "+bearer);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> taskRequest(long id,String bearer) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/documents/"+id+"/index/tasks"))
            .timeout(java.time.Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody());
        if(bearer!=null) builder.header("Authorization","Bearer "+bearer);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    void awaitTerminal(long id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while("PROCESSING".equals(indexes.status(id).state()) && System.nanoTime()<deadline) Thread.sleep(20);
        assertNotEquals("PROCESSING",indexes.status(id).state());
    }
    @Test void taskAcceptsBeforeModelCompletesAndRejectsDuplicate() throws Exception {
        long id=upload("后台索引文字"); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(embedding.embed(anyList())).thenAnswer(call->{
            entered.countDown(); assertTrue(release.await(6,TimeUnit.SECONDS));
            return List.of(new double[]{1,0});
        });
        try {
            var response=taskRequest(id,token);
            assertEquals(202,response.statusCode(),response.body());
            assertEquals("PROCESSING",json.readTree(response.body()).path("state").asText());
            assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals("PROCESSING",indexes.status(id).state());
            assertEquals(503,taskRequest(id,token).statusCode());
        } finally { release.countDown(); awaitTerminal(id); }
        assertEquals("READY",indexes.status(id).state()); verify(embedding,times(1)).embed(anyList());
    }
    @Test void failedBackgroundRebuildKeepsPublishedIndex() throws Exception {
        long id=upload("保留原索引"); indexes.build(id); String old=rows.find(id).activeCollection();
        when(embedding.embed(anyList())).thenThrow(new ChatException(503,"模型暂不可用"));
        assertEquals(202,taskRequest(id,token).statusCode()); awaitTerminal(id);
        assertEquals("FAILED",indexes.status(id).state()); assertEquals(old,rows.find(id).activeCollection());
        assertTrue(indexes.status(id).hasActiveIndex());
    }
    @Test void taskChecksAuthorizationBeforeRegisteringWork() throws Exception {
        long id=upload("权限验证"); assertEquals(401,taskRequest(id,null).statusCode());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",userId); access.assignRole(userId,"USER");
        assertEquals(403,taskRequest(id,token).statusCode());
        assertNull(rows.find(id)); verify(embedding,never()).embed(anyList());
    }
    HttpResponse<String> raw(String method,String path,String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:6333"+path))
            .header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> searchRequest(long id,String query,String bearer) throws Exception {
        return queryRequest(id,query,bearer,"search");
    }
    HttpResponse<String> baseRequest(long id,String action,String bearer) throws Exception {
        return baseRequest(id,action,bearer,Map.of("query","如何上传？"));
    }
    HttpResponse<String> baseRequest(long id,String action,String bearer,Map<String,Object> body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/knowledge-bases/"+id+"/"+action))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if(bearer!=null) builder.header("Authorization","Bearer "+bearer);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void rerankHttpReturnsValidatedOrderAndBaselineAndReusesAnswerSources() throws Exception {
        long a=upload("第一份原文"); long b=upload("第二份原文"); indexes.build(a); indexes.build(b);
        var body=Map.<String,Object>of("query","q","rerank",true);
        clearInvocations(llm);
        assertEquals(401,baseRequest(baseId,"search",null,body).statusCode()); verify(llm,never()).complete(anyList());
        when(reranker.select(anyString(),anyList())).thenReturn(List.of(1,0));
        var response=baseRequest(baseId,"search",token,body); assertEquals(200,response.statusCode(),response.body());
        var found=json.readTree(response.body()); assertTrue(found.path("rerank").path("applied").asBoolean());
        assertEquals(a,found.path("rerank").path("before").get(0).path("documentId").asLong());
        assertEquals(b,found.path("matches").get(0).path("documentId").asLong());
        verify(llm,never()).complete(anyList());
        when(llm.complete(anyList())).thenReturn(
            new LlmClient.Reply("{\"insufficient\":false,\"answer\":\"答\",\"sourceIds\":[1]}","glm-test",false));
        var answer=baseRequest(baseId,"answer",token,body); assertEquals(200,answer.statusCode(),answer.body());
        assertEquals("第二份原文",json.readTree(answer.body()).path("sources").get(0).path("text").asText());
        assertEquals("no-store",answer.headers().firstValue("cache-control").orElseThrow());
        var steps=json.readTree(answer.body()).path("retrieval").path("timings").path("steps");
        assertEquals(5,steps.size()); assertEquals(0,steps.get(0).path("calls").asInt());
        assertEquals(2,steps.get(1).path("calls").asInt()); assertEquals(0,steps.get(2).path("calls").asInt());
        assertEquals(1,steps.get(3).path("calls").asInt()); assertEquals(1,steps.get(4).path("calls").asInt());
    }
    @Test void hybridHttpUsesPublishedPayloadAndCallsEmbeddingOnce() throws Exception {
        long a=upload("普通说明"); long b=upload("必须使用 UTF-8 编码"); indexes.build(a); indexes.build(b);
        clearInvocations(embedding,llm,qdrant);
        var body=Map.<String,Object>of("query","编码要求","mode","hybrid","keywords",List.of("UTF-8"));
        assertEquals(401,baseRequest(baseId,"search",null,body).statusCode());
        var response=baseRequest(baseId,"search",token,body); assertEquals(200,response.statusCode(),response.body());
        var found=json.readTree(response.body()); assertEquals("hybrid",found.path("mode").asText());
        assertEquals(b,found.path("matches").get(0).path("documentId").asLong());
        assertEquals("utf-8",found.path("keywords").get(0).asText());
        verify(embedding,times(1)).embed(List.of("编码要求")); verify(qdrant).scanDocument(rows.find(a).activeCollection());
        verify(qdrant).scanDocument(rows.find(b).activeCollection()); verify(llm,never()).complete(anyList());
        var answer=baseRequest(baseId,"answer",token,body); assertEquals(200,answer.statusCode(),answer.body());
        assertEquals(b,json.readTree(answer.body()).path("sources").get(0).path("documentId").asLong());
        assertEquals("no-store",answer.headers().firstValue("cache-control").orElseThrow());
    }
    @Test void keywordScanRejectsIncompleteOrForeignPayloadAndChecksVersion() {
        long id=upload("正确文字"); indexes.build(id); var collection=rows.find(id).activeCollection();
        var points=qdrant.scanDocument(collection); clearInvocations(embedding);
        assertEquals("正确文字",searches.scanForKeywords(id,"文字").matches().get(0).text());
        verify(embedding,never()).embed(anyList());
        doReturn(json.createArrayNode()).when(qdrant).scanDocument(collection);
        assertEquals(502,assertThrows(ChatException.class,()->searches.scanForKeywords(id,"文字")).status());
        var wrong=points.deepCopy(); ((tools.jackson.databind.node.ObjectNode)wrong.get(0).path("payload")).put("documentId",id+1);
        doReturn(wrong).when(qdrant).scanDocument(collection);
        assertEquals(502,assertThrows(ChatException.class,()->searches.scanForKeywords(id,"文字")).status());
        doAnswer(call->{
            jdbc.update("UPDATE document_index SET active_collection=? WHERE document_id=?",collection+"changed",id);
            return points;
        }).when(qdrant).scanDocument(collection);
        assertEquals(409,assertThrows(ChatException.class,()->searches.scanForKeywords(id,"文字")).status());
    }
    @Test void invalidHybridInputDoesNotCallModels() throws Exception {
        var response=baseRequest(baseId,"search",token,Map.of("query","q","mode","hybrid","keywords",List.of()));
        assertEquals(400,response.statusCode()); verify(embedding,never()).embed(anyList()); verify(llm,never()).complete(anyList());
    }
    @Test void baseRetrievalExcludesOtherBasesAndAnswersWithCrossDocumentSources() throws Exception {
        long a=upload("上传要求甲"); long b=upload("账号要求乙"); long skipped=upload("尚未建立"); indexes.build(a); indexes.build(b);
        long other=bases.create("other_"+UUID.randomUUID().toString().substring(0,8),"").id();
        try {
            long outside=documents.upload(other,new MockMultipartFile("file","outside.txt","text/plain","其他知识库秘密内容".getBytes(StandardCharsets.UTF_8))).id();
            documentIds.add(outside); indexes.build(outside); clearInvocations(embedding,llm);
            assertEquals(401,baseRequest(baseId,"answer",null).statusCode());
            var response=baseRequest(baseId,"search",token); assertEquals(200,response.statusCode(),response.body());
            var found=json.readTree(response.body()); assertEquals(2,found.path("searchedDocuments").asInt());
            assertEquals(skipped,found.path("skipped").get(0).path("documentId").asLong());
            assertFalse(response.body().contains("其他知识库秘密内容")); verify(embedding,times(1)).embed(List.of("如何上传？"));
            when(llm.complete(anyList())).thenReturn(new LlmClient.Reply("{\"insufficient\":false,\"answer\":\"两个要求\",\"sourceIds\":[1,2]}","glm",false));
            var answer=baseRequest(baseId,"answer",token); assertEquals(200,answer.statusCode(),answer.body());
            assertEquals(2,json.readTree(answer.body()).path("sources").size());
            assertEquals("no-store",answer.headers().firstValue("cache-control").orElseThrow());
        } finally { jdbc.update("DELETE FROM document WHERE knowledge_base_id=?",other); jdbc.update("DELETE FROM knowledge_base WHERE id=?",other); }
    }
    @Test void baseQuestionRequiresAllThreePermissions() throws Exception {
        String code="RAG_"+UUID.randomUUID().toString().substring(0,8); jdbc.update("INSERT INTO app_role(code,label) VALUES (?,?)",code,code);
        long role=jdbc.queryForObject("SELECT id FROM app_role WHERE code=?",Long.class,code);
        try {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?",userId); jdbc.update("INSERT INTO app_user_role(user_id,role_id) VALUES (?,?)",userId,role);
            for(String missing:List.of("knowledge-base:read","document:read","chat:send")) {
                jdbc.update("DELETE FROM app_role_permission WHERE role_id=?",role);
                jdbc.update("INSERT INTO app_role_permission(role_id,permission_id) SELECT ?,id FROM app_permission WHERE code IN ('knowledge-base:read','document:read','chat:send') AND code<>?",role,missing);
                assertEquals(403,baseRequest(baseId,"search",token).statusCode()); assertEquals(403,baseRequest(baseId,"answer",token).statusCode());
            }
            verify(embedding,never()).embed(anyList()); verify(llm,never()).complete(anyList());
        } finally {
            jdbc.update("DELETE FROM app_user_role WHERE role_id=?",role); jdbc.update("DELETE FROM app_role_permission WHERE role_id=?",role); jdbc.update("DELETE FROM app_role WHERE id=?",role);
        }
    }
    HttpResponse<String> queryRequest(long id,String query,String bearer,String action) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/documents/"+id+"/"+action))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query))));
        if(bearer!=null) builder.header("Authorization","Bearer "+bearer);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void documentSearchUsesOnlyPublishedCollectionAndEmbedsOnlyQuestion() throws Exception {
        long id=upload("索引范围甲"); indexes.build(id); String old=rows.find(id).activeCollection();
        indexes.build(id); String active=rows.find(id).activeCollection();
        long other=upload("其他文档乙"); indexes.build(other);
        clearInvocations(embedding,qdrant);
        var response=searchRequest(id,"  上传步骤是什么？  ",token); assertEquals(200,response.statusCode(),response.body());
        var result=json.readTree(response.body()); assertEquals(id,result.path("documentId").asLong());
        assertEquals(baseId,result.path("knowledgeBaseId").asLong());
        assertEquals("索引范围甲",result.path("matches").get(0).path("text").asText());
        assertEquals(0,result.path("matches").get(0).path("startOffset").asInt());
        assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
        assertFalse(response.body().contains(active));
        verify(embedding).embed(List.of("上传步骤是什么？"));
        verify(qdrant).search(eq(active),any(double[].class)); verify(qdrant,never()).search(eq(old),any(double[].class));
    }
    @Test void failedRebuildCanStillSearchPublishedVersion() {
        long id=upload("保留的正文"); indexes.build(id);
        when(embedding.embed(anyList())).thenThrow(new ChatException(503,"模型暂不可用"));
        assertThrows(ChatException.class,()->indexes.build(id));
        doReturn(List.of(new double[]{1,0})).when(embedding).embed(anyList());
        var result=searches.search(id,"正文是什么");
        assertTrue(result.usingPreviousVersion()); assertEquals("保留的正文",result.matches().get(0).text());
    }
    @Test void searchRejectsMissingIndexInvalidInputAndModelMismatchBeforeEmbedding() throws Exception {
        long id=upload("说明");
        assertEquals(401,searchRequest(id,"问题",null).statusCode());
        assertEquals(400,searchRequest(id," ",token).statusCode());
        assertEquals(400,searchRequest(id,"字".repeat(1001),token).statusCode());
        assertEquals(409,searchRequest(id,"问题",token).statusCode());
        verify(embedding,never()).embed(anyList());
        indexes.build(id); clearInvocations(embedding);
        when(embedding.spaceId()).thenReturn("changed-space");
        assertEquals(409,searchRequest(id,"问题",token).statusCode()); verify(embedding,never()).embed(anyList());
    }
    @Test void searchRejectsWrongDocumentPayloadAndReleasesCapacityAfterFailure() throws Exception {
        long id=upload("正确正文"); indexes.build(id); var row=rows.find(id);
        var original=qdrant.search(row.activeCollection(),new double[]{1,0});
        var wrong=original.deepCopy();
        ((tools.jackson.databind.node.ObjectNode)wrong.get(0).path("payload")).put("documentId",id+1);
        doReturn(wrong).when(qdrant).search(eq(row.activeCollection()),any(double[].class));
        assertEquals(502,searchRequest(id,"问题",token).statusCode());
        doCallRealMethod().when(qdrant).search(anyString(),any(double[].class));
        assertEquals("正确正文",searches.search(id,"问题").matches().get(0).text());
    }
    @Test void searchDoesNotReturnStaleResultWhenActiveVersionChangesDuringEmbedding() {
        long id=upload("版本切换"); indexes.build(id);
        when(embedding.embed(anyList())).thenAnswer(call->{
            jdbc.update("UPDATE document_index SET active_collection=? WHERE document_id=?","document27_"+id+"_new",id);
            return List.of(new double[]{1,0});
        });
        var failure=assertThrows(ChatException.class,()->searches.search(id,"问题"));
        assertEquals(409,failure.status()); assertTrue(failure.getMessage().contains("已更新"));
    }
    @Test void searchNeedsBothReadAndModelPermissions() throws Exception {
        long id=upload("权限说明"); indexes.build(id);
        String code="TEST_"+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO app_role(code,label) VALUES (?,?)",code,code);
        long role=jdbc.queryForObject("SELECT id FROM app_role WHERE code=?",Long.class,code);
        try {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?",userId);
            jdbc.update("INSERT INTO app_user_role(user_id,role_id) VALUES (?,?)",userId,role);
            jdbc.update("INSERT INTO app_role_permission(role_id,permission_id) SELECT ?,id FROM app_permission WHERE code='document:read'",role);
            clearInvocations(embedding);
            assertEquals(403,searchRequest(id,"问题",token).statusCode());
            assertEquals(403,queryRequest(id,"问题",token,"answer").statusCode());
            jdbc.update("DELETE FROM app_role_permission WHERE role_id=?",role);
            jdbc.update("INSERT INTO app_role_permission(role_id,permission_id) SELECT ?,id FROM app_permission WHERE code='chat:send'",role);
            assertEquals(403,searchRequest(id,"问题",token).statusCode()); verify(embedding,never()).embed(anyList());
            assertEquals(403,queryRequest(id,"问题",token,"answer").statusCode()); verify(llm,never()).complete(anyList());
            jdbc.update("INSERT INTO app_role_permission(role_id,permission_id) SELECT ?,id FROM app_permission WHERE code='document:read'",role);
            assertEquals(200,searchRequest(id,"问题",token).statusCode());
        } finally {
            jdbc.update("DELETE FROM app_user_role WHERE role_id=?",role);
            jdbc.update("DELETE FROM app_role_permission WHERE role_id=?",role);
            jdbc.update("DELETE FROM app_role WHERE id=?",role);
        }
    }
    @Test void documentAnswerEndpointReturnsServerResolvedSourcesAndProtectsAnonymousAccess() throws Exception {
        long id=upload("选择文件后上传。"); indexes.build(id);
        assertEquals(401,queryRequest(id,"如何上传？",null,"answer").statusCode());
        var response=queryRequest(id,"如何上传？",token,"answer");
        assertEquals(200,response.statusCode(),response.body()); assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
        var answer=json.readTree(response.body());
        assertEquals("选择文件后上传。",answer.path("sources").get(0).path("text").asText());
        assertEquals(id,answer.path("documentId").asLong()); assertEquals("source.txt",answer.path("fileName").asText());
        assertFalse(response.body().contains(rows.find(id).activeCollection()));
        verify(llm,times(1)).complete(anyList());
    }
    @AfterEach void cleanup() throws Exception {
        var collections=json.readTree(raw("GET","/collections",null).body()).path("result").path("collections");
        for(var collection:collections) for(long id:documentIds) if(collection.path("name").asText().startsWith("document27_"+id+"_"))
            raw("DELETE","/collections/"+collection.path("name").asText(),null);
        jdbc.update("DELETE FROM document WHERE knowledge_base_id=?",baseId);
        jdbc.update("DELETE FROM knowledge_base WHERE id=?",baseId); jdbc.update("DELETE FROM app_user WHERE id=?",userId);
        var client=minio();
        if(client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            for(var result:client.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build()))
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(result.get().objectName()).build());
        }
    }
    @AfterAll static void finish() throws Exception {
        var client=minio();
        if(client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) client.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
    }
    @Test void minioToQdrantPublishesCompleteVersionWithProvenanceAndLeavesOriginalUntouched() throws Exception {
        String source="上传文件需要先选择知识库。\n".repeat(60); long id=upload(source);
        assertEquals("NOT_INDEXED",indexes.status(id).state());
        var response=request("POST",id,token); assertEquals(200,response.statusCode(),response.body());
        var state=indexes.status(id); assertEquals("READY",state.state()); assertTrue(state.currentModel());
        var row=rows.find(id); var chunks=TextChunker.split(source,800,100);
        assertEquals(chunks.size(),state.chunks()); assertEquals(source.length(),state.characters());
        var points=json.readTree(raw("POST","/collections/"+row.activeCollection()+"/points/scroll","{\"limit\":100,\"with_payload\":true}").body()).path("result").path("points");
        assertEquals(chunks.size(),points.size());
        for(var point:points) {
            var payload=point.path("payload"); int index=payload.path("chunkIndex").asInt();
            assertEquals(id,payload.path("documentId").asLong()); assertEquals(baseId,payload.path("knowledgeBaseId").asLong());
            assertEquals(chunks.get(index).text(),payload.path("text").asText());
            assertEquals(row.sourceSha256(),payload.path("sourceSha256").asText());
        }
        assertArrayEquals(source.getBytes(StandardCharsets.UTF_8),documents.download(id).bytes());
        assertEquals("UPLOADED",documentMapper.findById(id).status());
        assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
        indexes.build(id); assertNotEquals(row.activeCollection(),rows.find(id).activeCollection());
    }
    @Test void failedRebuildKeepsPreviousVersionAndUnconfirmedWriteIsCleanedUp() {
        long id=upload("原始内容"); indexes.build(id); var previous=rows.find(id).activeCollection();
        doAnswer(call->{ call.callRealMethod(); throw new ChatException(504,"模拟写入确认丢失"); }).when(qdrant).upsert(anyString(),anyList());
        assertThrows(ChatException.class,()->indexes.build(id));
        assertEquals("FAILED",indexes.status(id).state()); assertTrue(indexes.status(id).hasActiveIndex());
        assertEquals(previous,rows.find(id).activeCollection()); assertNotNull(qdrant.info(previous));
        verify(qdrant).remove(argThat(name->!name.equals(previous)));
    }
    @Test void inputAndPermissionFailuresDoNotSendTextToModel() throws Exception {
        long id=upload("字".repeat(4001));
        assertEquals(401,request("POST",id,null).statusCode());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",userId); access.assignRole(userId,"USER");
        assertEquals(403,request("POST",id,token).statusCode()); assertEquals(200,request("GET",id,token).statusCode());
        access.assignRole(userId,"EDITOR"); assertEquals(400,request("POST",id,token).statusCode());
        assertEquals("FAILED",indexes.status(id).state()); assertFalse(indexes.status(id).hasActiveIndex());
        long empty=upload(" \n "); assertEquals(400,request("POST",empty,token).statusCode());
        verify(embedding,never()).embed(anyList());
    }
    @Test void secondBatchFailurePublishesNothingAndModelChangeMarksOldVersionIncompatible() {
        long id=upload("字".repeat(4000)); var calls=new AtomicInteger();
        when(embedding.embed(anyList())).thenAnswer(call->{
            if(calls.incrementAndGet()==2) throw new ChatException(503,"模拟第二批限流");
            return ((List<?>)call.getArgument(0)).stream().map(x->new double[]{1,0}).toList();
        });
        assertThrows(ChatException.class,()->indexes.build(id)); assertEquals(2,calls.get());
        assertNull(rows.find(id).activeCollection()); verify(qdrant,never()).upsert(anyString(),anyList());
        when(embedding.embed(anyList())).thenAnswer(call->((List<?>)call.getArgument(0)).stream().map(x->new double[]{1,0}).toList());
        indexes.build(id); when(embedding.spaceId()).thenReturn("new-space"); assertFalse(indexes.status(id).currentModel());
    }
    @Test void processingIsVisibleAndDuplicateBuildDoesNotCallModelAgain() throws Exception {
        long id=upload("文字"); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(embedding.embed(anyList())).thenAnswer(call->{ entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); return List.of(new double[]{1,0}); });
        var pool=Executors.newSingleThreadExecutor();
        try {
            var work=pool.submit(()->indexes.build(id)); assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals("PROCESSING",indexes.status(id).state()); assertThrows(ChatException.class,()->indexes.build(id));
            release.countDown(); assertEquals("READY",work.get(10,TimeUnit.SECONDS).state());
            verify(embedding,times(1)).embed(anyList());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void expiredTaskCanBeRecoveredAndRetriesOnlyFailedEmbeddingBatch() throws Exception {
        long id=upload("恢复文字"); rows.initialize(id); rows.claim(id,"abandoned");
        assertFalse(indexes.status(id).recoveryAllowed());
        assertEquals(409,taskRequest(id,token).statusCode());
        jdbc.update("UPDATE document_index SET updated_at=CURRENT_TIMESTAMP - INTERVAL 11 MINUTE WHERE document_id=?",id);
        assertTrue(indexes.status(id).recoveryAllowed());
        when(embedding.embed(anyList())).thenThrow(new com.example.aiknowledge.exception.RetryableEmbeddingException())
            .thenReturn(List.of(new double[]{1,0}));
        assertEquals(202,taskRequest(id,token).statusCode()); awaitTerminal(id);
        assertEquals("READY",indexes.status(id).state()); assertFalse(indexes.status(id).recoveryAllowed());
        verify(embedding,times(2)).embed(anyList()); verify(qdrant,times(1)).upsert(anyString(),anyList());
        assertEquals(0,rows.fail(id,"abandoned","late failure"));
    }
    @Test void expiredAttemptCannotPublishOverNewAttempt() {
        long id=upload("重试"); rows.initialize(id); assertEquals(1,rows.claim(id,"old"));
        assertEquals(0,rows.claim(id,"new"));
        jdbc.update("UPDATE document_index SET updated_at=CURRENT_TIMESTAMP - INTERVAL 11 MINUTE WHERE document_id=?",id);
        assertEquals(1,rows.claim(id,"new"));
        assertEquals(0,rows.publish(id,"old","old-collection","m","s","sha",1,2,2,"note"));
        assertEquals("new",rows.find(id).attemptId()); assertNull(rows.find(id).activeCollection());
    }
}
