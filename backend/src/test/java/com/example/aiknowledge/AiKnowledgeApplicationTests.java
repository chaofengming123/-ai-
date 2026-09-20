package com.example.aiknowledge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiKnowledgeApplicationTests {

    @LocalServerPort
    private int port;

    private HttpResponse<String> request(String method, String path) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthReturnsServiceStatusAsJson() throws Exception {
        var response = request("GET", "/api/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
        assertEquals("UP", json.get("status").asText());
        assertEquals("ai-knowledge-backend", json.get("service").asText());
        assertEquals(2, json.size());
    }

    @Test
    void healthRejectsPost() throws Exception {
        assertEquals(405, request("POST", "/api/health").statusCode());
    }

    @Test
    void unknownEndpointReturnsNotFound() throws Exception {
        assertEquals(404, request("GET", "/api/not-found").statusCode());
    }
}
