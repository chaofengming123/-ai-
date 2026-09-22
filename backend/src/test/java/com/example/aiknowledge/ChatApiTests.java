package com.example.aiknowledge;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import com.example.aiknowledge.service.*;
import com.example.aiknowledge.model.ChatMessage;
import com.example.aiknowledge.exception.ChatException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.jdbc.Sql(statements="DELETE FROM app_user",
    executionPhase=org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ChatApiTests {
    static HttpServer upstream;
    static final ExecutorService workers=Executors.newCachedThreadPool();
    static final AtomicInteger calls=new AtomicInteger();
    static final AtomicReference<String> received=new AtomicReference<>(), authorization=new AtomicReference<>();
    static volatile int status=200;
    static volatile String response;
    static volatile boolean stall;
    static final String OK="{\"choices\":[{\"message\":{\"content\":\"Controller 接收请求。\"},\"finish_reason\":\"stop\"}]}";
    static {
        try {
            upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.setExecutor(workers);
            upstream.createContext("/chat",exchange->{
                calls.incrementAndGet();
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                if(status==302) exchange.getResponseHeaders().set("Location","/chat");
                exchange.sendResponseHeaders(status,bytes.length);
                try {
                    if(stall) { exchange.getResponseBody().write(bytes,0,1); exchange.getResponseBody().flush(); Thread.sleep(2200); }
                    else exchange.getResponseBody().write(bytes);
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            upstream.start();
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.llm.endpoint",()->"http://127.0.0.1:"+upstream.getAddress().getPort()+"/chat");
        registry.add("app.llm.api-key",()->"test-provider-key");
        registry.add("app.llm.model",()->"test-model");
        registry.add("app.llm.timeout-seconds",()->1);
        registry.add("app.llm.token-parameter",()->"max_tokens");
        registry.add("app.llm.thinking",()->"disabled");
    }
    @AfterAll static void stop() { upstream.stop(0); workers.shutdownNow(); }
    @BeforeEach void reset() { status=200; response=OK; calls.set(0); stall=false; }
    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired LoginService login;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    String token;
    long userId;
    void signIn() {
        var user=users.register("chat_"+UUID.randomUUID().toString().substring(0,8),"test-password");
        userId=user.id(); token=login.login(user.username(),"test-password").accessToken();
    }
    HttpResponse<String> request(String method,String path,String body,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .header("Content-Type","application/json").timeout(java.time.Duration.ofSeconds(10))
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        if(token!=null) builder.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    String question="{\"messages\":[{\"role\":\"user\",\"content\":\"解释 Controller\"}]}";
    @Test void authenticatedChatUsesServerCredentialsAndPrependsSystemMessage() throws Exception {
        signIn();
        var config=request("GET","/api/chat/config",null,token);
        assertEquals(200,config.statusCode());
        assertEquals(Set.of("configured","model"),json.readTree(config.body()).propertyNames());
        assertEquals("no-store",config.headers().firstValue("cache-control").orElseThrow());
        var result=request("POST","/api/chat",question,token);
        assertEquals(200,result.statusCode(),result.body());
        assertEquals("Controller 接收请求。",json.readTree(result.body()).get("content").asText());
        var sent=json.readTree(received.get());
        assertEquals("test-model",sent.get("model").asText());
        assertEquals(800,sent.get("max_tokens").asInt());
        assertFalse(sent.get("stream").asBoolean());
        assertEquals("disabled",sent.get("thinking").get("type").asText());
        assertEquals("system",sent.get("messages").get(0).get("role").asText());
        assertEquals("user",sent.get("messages").get(1).get("role").asText());
        assertEquals("Bearer test-provider-key",authorization.get());
        assertFalse(result.body().contains("test-provider-key"));
    }
    @Test void authenticationAndPermissionFailuresNeverReachModel() throws Exception {
        assertEquals(401,request("POST","/api/chat",question,null).statusCode());
        assertEquals(401,request("GET","/api/chat/config",null,null).statusCode());
        signIn(); jdbc.update("DELETE FROM app_user_role WHERE user_id=?",userId);
        assertEquals(403,request("POST","/api/chat",question,token).statusCode());
        assertEquals(0,calls.get());
    }
    @Test void invalidMessagesNeverReachModel() throws Exception {
        signIn();
        for(String body:List.of("{}","{\"messages\":[]}","{\"messages\":[null]}",
                question.replace("user","system"),question.replace("解释 Controller"," "),
                question.replace("解释 Controller","x".repeat(2001)))) {
            assertEquals(400,request("POST","/api/chat",body,token).statusCode(),body.substring(0,Math.min(body.length(),80)));
        }
        assertEquals(0,calls.get());
    }
    @Test void upstreamFailuresStayDistinctFromApplicationLoginAndDoNotRetry() throws Exception {
        signIn();
        for(int code:new int[]{401,403,429,500,302}) {
            status=code; response="private upstream detail"; int before=calls.get();
            var result=request("POST","/api/chat",question,token);
            assertEquals(code==429?503:502,result.statusCode());
            assertFalse(result.body().contains(response));
            assertEquals(before+1,calls.get());
        }
        status=200; response=OK.replace("stop","length");
        assertTrue(json.readTree(request("POST","/api/chat",question,token).body()).get("truncated").asBoolean());
    }
    @Test void malformedAndOversizedResponsesAreRejected() throws Exception {
        signIn();
        for(String body:List.of("not json","{}","x".repeat(270000))) {
            response=body;
            assertEquals(502,request("POST","/api/chat",question,token).statusCode());
        }
    }
    @Test void timeoutIncludesResponseBodyAndReleasesUserSlot() throws Exception {
        signIn(); stall=true;
        assertEquals(504,request("POST","/api/chat",question,token).statusCode());
        stall=false;
        assertEquals(200,request("POST","/api/chat",question,token).statusCode());
    }
    @Test void absentConfigurationDoesNotFakeAReply() {
        var client=new LlmClient("","","",45,"max_tokens","disabled");
        assertFalse(client.configuration().configured());
        assertEquals(503,assertThrows(ChatException.class,()->client.complete(List.of(new ChatMessage("user","hi")))).status());
    }
}
