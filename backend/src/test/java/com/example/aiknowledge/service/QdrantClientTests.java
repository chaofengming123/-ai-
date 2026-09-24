package com.example.aiknowledge.service;

import com.example.aiknowledge.exception.ChatException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QdrantClientTests {
    @Test void acceptsLoopbackAndExactComposeService() {
        for(String endpoint : new String[]{"http://127.0.0.1:6333", "http://localhost:6333",
            "http://[::1]:6333", "http://localhost:12345", "http://qdrant:6333"}) {
            assertDoesNotThrow(() -> QdrantClient.validateEndpoint(endpoint), endpoint);
        }
    }

    @Test void rejectsExternalHostsAndNonRootUrls() {
        for(String endpoint : new String[]{"http://example.com:6333", "http://qdrant.example.com:6333",
            "http://qdrant", "http://qdrant:1234", "https://qdrant:6333", "http://192.168.1.2:6333",
            "http://user@qdrant:6333", "http://qdrant:6333/api", "http://qdrant:6333?x=1",
            "http://qdrant:6333#fragment", "http://localhost:0", "http://localhost:65536", "invalid url"}) {
            assertThrows(ChatException.class, () -> QdrantClient.validateEndpoint(endpoint), endpoint);
        }
    }

    @Test void readsCollectionThroughHttpAndNormalizesTrailingSlash() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/collections/test", exchange -> {
            byte[] body="{\"result\":{\"points_count\":3}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client=new QdrantClient(" http://127.0.0.1:"+server.getAddress().getPort()+"/ ");
            assertEquals(3,client.info("test").path("points_count").asInt());
        } finally { server.stop(0); }
    }
}
