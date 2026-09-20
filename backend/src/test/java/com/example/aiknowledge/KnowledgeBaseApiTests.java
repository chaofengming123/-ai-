package com.example.aiknowledge;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KnowledgeBaseApiTests {
    @LocalServerPort private int port;
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void listsSeedDataAndReadsDetail() throws Exception {
        var response = request("GET", "/api/knowledge-bases", null);
        assertEquals(200, response.statusCode());
        JsonNode records = json.readTree(response.body());
        assertTrue(records.isArray());
        assertEquals("公司制度知识库", records.get(0).get("name").asText());
        assertEquals("工程技术知识库", records.get(1).get("name").asText());
        var detail = request("GET", "/api/knowledge-bases/1", null);
        assertEquals(200, detail.statusCode());
        assertEquals(3, json.readTree(detail.body()).get("documentCount").asInt());
    }

    @Test void createsNormalizesAndMakesRecordReadable() throws Exception {
        String name = "测试-" + UUID.randomUUID();
        var response = request("POST", "/api/knowledge-bases", "{\"name\":\"  " + name + "  \",\"description\":\"  说明  \"}");
        assertEquals(201, response.statusCode());
        var created = json.readTree(response.body());
        assertEquals(name, created.get("name").asText());
        assertEquals("说明", created.get("description").asText());
        assertEquals(0, created.get("documentCount").asInt());
        String location = response.headers().firstValue("Location").orElseThrow();
        var detail = request("GET", location, null);
        assertEquals(200, detail.statusCode());
        assertEquals(created, json.readTree(detail.body()));
        assertEquals(409, request("POST", "/api/knowledge-bases", "{\"name\":\"" + name + "\"}").statusCode());
    }

    @Test void validatesRequestsWithoutAddingRecords() throws Exception {
        int before = json.readTree(request("GET", "/api/knowledge-bases", null).body()).size();
        for (String body : new String[]{"{}", "{\"name\":null}", "{\"name\":\"   \"}", "null", "not-json",
                "{\"name\":\"" + "a".repeat(61) + "\"}", "{\"name\":\"ok\",\"description\":\"" + "x".repeat(301) + "\"}"}) {
            var response = request("POST", "/api/knowledge-bases", body);
            assertEquals(400, response.statusCode(), body);
            assertTrue(json.readTree(response.body()).get("message").isTextual());
        }
        assertEquals(before, json.readTree(request("GET", "/api/knowledge-bases", null).body()).size());
    }

    @Test void defaultsOptionalDescriptionAndRejectsBadIds() throws Exception {
        var response = request("POST", "/api/knowledge-bases", "{\"name\":\"默认-" + UUID.randomUUID() + "\"}");
        assertEquals(201, response.statusCode());
        assertEquals("暂无描述", json.readTree(response.body()).get("description").asText());
        assertEquals(404, request("GET", "/api/knowledge-bases/999999", null).statusCode());
        assertEquals(400, request("GET", "/api/knowledge-bases/abc", null).statusCode());
    }
}
