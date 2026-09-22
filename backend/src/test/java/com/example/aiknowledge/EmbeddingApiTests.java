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
@org.springframework.test.context.jdbc.Sql(statements="DELETE FROM app_user",executionPhase=org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD)
class EmbeddingApiTests {
    static HttpServer upstream;
    static final AtomicInteger calls=new AtomicInteger();
    static final AtomicReference<String> received=new AtomicReference<>(),authorization=new AtomicReference<>();
    static volatile String body;
    static volatile int status;
    static {
        try {
            upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.createContext("/embed",exchange->{
                calls.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); upstream.start();
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("app.embedding.endpoint",()->"http://127.0.0.1:"+upstream.getAddress().getPort()+"/embed");
        r.add("app.embedding.model",()->"test-model");
        r.add("app.embedding.api-key",()->"embedding-test-key"); r.add("app.embedding.timeout-seconds",()->1);
    }
    static final String VALID="{\"data\":[{\"index\":2,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[1,0]},{\"index\":1,\"embedding\":[0,1]}]}";
    @BeforeEach void reset() { calls.set(0); status=200; body=VALID; }
    @AfterAll static void stop() { upstream.stop(0); }
    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired LoginService login;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    long id;
    String login() {
        var user=users.register("emb_"+UUID.randomUUID().toString().substring(0,8),"test-password"); id=user.id();
        return login.login(user.username(),"test-password").accessToken();
    }
    String query="{\"query\":\"上传文件\",\"candidates\":[\"做饭\",\"文档上传\"]}";
    HttpResponse<String> request(String payload,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/embeddings/compare"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload));
        if(token!=null) builder.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void cloudRequestProducesRankedVectorsWithIndependentCredentials() throws Exception {
        var result=request(query,login()); assertEquals(200,result.statusCode(),result.body());
        var data=json.readTree(result.body());
        assertEquals(2,data.get("dimensions").asInt());
        assertEquals(1,data.get("matches").get(0).get("index").asInt());
        assertEquals(1,data.get("matches").get(0).get("similarity").asDouble());
        assertEquals("float",json.readTree(received.get()).path("encoding_format").asText());
        assertEquals("Bearer embedding-test-key",authorization.get()); assertEquals(1,calls.get());
        assertEquals("no-store",result.headers().firstValue("cache-control").orElseThrow());
    }
    @Test void badInputAndMissingPermissionNeverCallProvider() throws Exception {
        assertEquals(401,request(query,null).statusCode());
        String token=login();
        assertEquals(400,request("{}",token).statusCode());
        assertEquals(400,request(query.replace("上传文件","字".repeat(1001)),token).statusCode());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",id);
        assertEquals(403,request(query,token).statusCode()); assertEquals(0,calls.get());
    }
    @Test void missingModelAndBadResponseFailWithoutInventingVectorsOrRetrying() throws Exception {
        String token=login(); status=404;
        assertEquals(503,request(query,token).statusCode()); assertEquals(1,calls.get());
        status=200; body="{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}";
        assertEquals(502,request(query,token).statusCode()); assertEquals(2,calls.get());
        body=VALID;
        assertEquals(200,request(query,token).statusCode()); // 失败后释放计算名额。
    }
}
