package com.example.aiknowledge;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import com.example.aiknowledge.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class VectorStorageApiTests {
    static final JsonMapper json=JsonMapper.builder().build();
    static final AtomicInteger calls=new AtomicInteger();
    static final AtomicReference<String> received=new AtomicReference<>();
    static final String model="test26-"+UUID.randomUUID();
    static volatile int upstreamStatus=200;
    static final HttpServer upstream;
    static {
        try {
            upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.createContext("/embed",exchange->{
                calls.incrementAndGet();
                String input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); received.set(input);
                var texts=json.readTree(input).path("input"); var data=new ArrayList<Map<String,Object>>();
                for(int i=0;i<texts.size();i++) data.add(Map.of("index",i,"embedding",texts.get(i).asText().contains("上传")?List.of(1,0):List.of(0,1)));
                byte[] body=json.writeValueAsBytes(Map.of("data",data));
                exchange.sendResponseHeaders(upstreamStatus,body.length); exchange.getResponseBody().write(body); exchange.close();
            }); upstream.start();
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("app.embedding.endpoint",()->"http://127.0.0.1:"+upstream.getAddress().getPort()+"/embed");
        r.add("app.embedding.model",()->model); r.add("app.embedding.api-key",()->"test-only-key");
        r.add("app.qdrant.endpoint",()->"http://127.0.0.1:6333");
    }
    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired LoginService login;
    @Autowired EmbeddingClient embedding;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    final List<Long> ids=new ArrayList<>();
    @BeforeEach void reset() { calls.set(0); upstreamStatus=200; }
    @AfterAll static void stop() { upstream.stop(0); }
    String login() {
        var user=users.register("vec_"+UUID.randomUUID().toString().substring(0,8),"test-password"); ids.add(user.id());
        return login.login(user.username(),"test-password").accessToken();
    }
    String collection(long id) { return "lesson26_u"+id+"_"+embedding.spaceId(); }
    HttpResponse<String> qdrant(String method,String path,String payload) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:6333"+path))
            .header("Content-Type","application/json").method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @AfterEach void cleanup() throws Exception {
        for(long id:ids) {
            qdrant("DELETE","/collections/"+collection(id),null);
            jdbc.update("DELETE FROM app_user WHERE id=?",id);
        }
    }
    HttpResponse<String> request(String method,String suffix,String payload,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/embeddings/stored"+suffix))
            .header("Content-Type","application/json").method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(payload));
        if(token!=null) builder.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    static final String TEXTS="{\"texts\":[\"上传文档说明\",\"炒菜说明\"]}";
    @Test void savedPointsSurviveNewRequestsAndOnlyQuestionIsEmbeddedForSearch() throws Exception {
        var token=login();
        var saved=request("POST","",TEXTS,token); assertEquals(200,saved.statusCode(),saved.body());
        assertEquals(2,json.readTree(saved.body()).path("count").asInt());
        assertEquals(200,request("POST","",TEXTS,token).statusCode());
        var status=request("GET","",null,token);
        assertEquals(2,json.readTree(status.body()).path("count").asInt());
        assertEquals("no-store",status.headers().firstValue("cache-control").orElseThrow());
        var found=request("POST","/search","{\"query\":\"如何上传？\"}",token);
        assertEquals(200,found.statusCode(),found.body());
        assertEquals("上传文档说明",json.readTree(found.body()).path("matches").get(0).path("text").asText());
        assertEquals(1,json.readTree(received.get()).path("input").size());
        assertEquals(3,calls.get());
        // 新 Service 实例没有内存候选列表，仍可读取 Qdrant 的已保存记录。
        var fresh=new VectorLabService(embedding,new QdrantClient("http://127.0.0.1:6333"));
        assertEquals(2,fresh.status(ids.get(0)).count());
        var other=login();
        assertEquals(0,json.readTree(request("GET","",null,other).body()).path("count").asInt());
        assertEquals(409,request("POST","/search","{\"query\":\"上传\"}",other).statusCode());
        assertEquals(3,calls.get());
    }
    @Test void unauthorizedAndInvalidRequestsDoNotCallModelOrCreateCollections() throws Exception {
        assertEquals(401,request("POST","",TEXTS,null).statusCode());
        assertEquals(401,request("GET","",null,null).statusCode());
        var token=login();
        assertEquals(400,request("POST","","{\"texts\":[\" \" ]}",token).statusCode());
        assertEquals(400,request("POST","/search","{}",token).statusCode());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",ids.get(0));
        assertEquals(403,request("POST","",TEXTS,token).statusCode());
        assertEquals(403,request("POST","/search","{\"query\":\"上传\"}",token).statusCode());
        assertEquals(0,calls.get());
        assertEquals(404,qdrant("GET","/collections/"+collection(ids.get(0)),null).statusCode());
    }
    @Test void modelFailureLeavesNoPointsAndWrongDimensionsAreNotSilentlyReplaced() throws Exception {
        var token=login(); upstreamStatus=429;
        assertEquals(503,request("POST","",TEXTS,token).statusCode()); assertEquals(1,calls.get());
        assertEquals(404,qdrant("GET","/collections/"+collection(ids.get(0)),null).statusCode());
        upstreamStatus=200;
        assertEquals(200,qdrant("PUT","/collections/"+collection(ids.get(0)),"{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}").statusCode());
        assertEquals(409,request("POST","",TEXTS,token).statusCode());
        var info=json.readTree(qdrant("GET","/collections/"+collection(ids.get(0)),null).body()).path("result");
        assertEquals(0,info.path("points_count").asInt());
        assertEquals(3,info.path("config").path("params").path("vectors").path("size").asInt());
    }
    @Test void modelSpaceChangesWithEndpointOrModelButNotKeyRotation() {
        var a=new EmbeddingClient("https://api.example.com/v1/embeddings","m",90,"old");
        assertEquals(a.spaceId(),new EmbeddingClient("https://api.example.com/v1/embeddings","m",90,"new").spaceId());
        assertNotEquals(a.spaceId(),new EmbeddingClient("https://other.example.com/v1/embeddings","m",90,"old").spaceId());
        assertNotEquals(a.spaceId(),new EmbeddingClient("https://api.example.com/v1/embeddings","m2",90,"old").spaceId());
    }
}
