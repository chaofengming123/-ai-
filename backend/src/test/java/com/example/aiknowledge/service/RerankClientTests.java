package com.example.aiknowledge.service;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;
import com.example.aiknowledge.exception.ChatException;

class RerankClientTests {
    HttpServer server;
    final JsonMapper json=JsonMapper.builder().build();
    final AtomicInteger calls=new AtomicInteger();
    String body="{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.3}]}";
    volatile String received,authorization;
    int status=200; long delay=0;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/rerank",exchange->{
            calls.incrementAndGet(); received=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            authorization=exchange.getRequestHeaders().getFirst("Authorization");
            try { Thread.sleep(delay); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            if(status==302) exchange.getResponseHeaders().add("Location","/rerank");
            try { exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    RerankClient client() { return new RerankClient("http://127.0.0.1:"+server.getAddress().getPort()+"/rerank","BAAI/bge-reranker-v2-m3",1,"test-key"); }
    @Test void sendsDedicatedPayloadAndMapsZeroBasedIndicesWithoutChatMessages() {
        assertEquals(List.of(1,0),client().select("编码？",List.of("其他","UTF-8")));
        var sent=json.readTree(received);
        assertEquals("Bearer test-key",authorization); assertEquals("BAAI/bge-reranker-v2-m3",sent.path("model").asText());
        assertEquals("UTF-8",sent.path("documents").get(1).asText()); assertEquals(2,sent.path("top_n").asInt());
        assertFalse(sent.path("return_documents").asBoolean()); assertFalse(sent.has("messages")); assertEquals(1,calls.get());
    }
    @Test void rejectsMalformedCountsIndicesAndNonfiniteOrUnsortedScores() {
        for(String invalid:List.of("{}","{\"results\":[]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}",
            "{\"results\":[{\"index\":2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}",
            "{\"results\":[{\"index\":0.5,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.9}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":1e400},{\"index\":1,\"relevance_score\":0.2}]}"))
            assertThrows(ChatException.class,()->client().decode(json.readTree(invalid),2));
    }
    @Test void refusesProviderErrorsRedirectsAndInvalidJsonWithoutRetryOrLeakingBody() {
        for(int code:List.of(401,403,404,429,500,302,200)) {
            status=code; body="private-upstream-message";
            var failure=assertThrows(ChatException.class,()->client().select("q",List.of("a","b")));
            assertFalse(failure.getMessage().contains(body));
        }
        assertEquals(7,calls.get());
    }
    @Test void timeoutDoesNotRetry() {
        delay=1500;
        assertEquals(504,assertThrows(ChatException.class,()->client().select("q",List.of("a","b"))).status());
        assertEquals(1,calls.get());
    }
    @Test void boundedInputsAndZeroOrOneCandidateDoNotCallProvider() {
        assertEquals(List.of(),client().select("q",List.of())); assertEquals(List.of(0),client().select("q",List.of("a")));
        assertThrows(ChatException.class,()->client().select("q",Collections.nCopies(7,"a")));
        assertThrows(ChatException.class,()->client().select("q",List.of("a".repeat(801)))); assertEquals(0,calls.get());
    }
    @Test void configurationRequiresIndependentKeyAndHttpsExceptLoopbackAndAcceptsQuotes() {
        assertFalse(new RerankClient("https://api.example/rerank","m",45,"").configuration().configured());
        assertFalse(new RerankClient("http://api.example/rerank","m",45,"key").configuration().configured());
        assertTrue(new RerankClient("'https://api.siliconflow.cn/v1/rerank'","'BAAI/bge-reranker-v2-m3'",45,"'key'").configuration().configured());
    }
}
