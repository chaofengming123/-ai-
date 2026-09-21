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

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.jdbc.Sql(statements = {"DELETE FROM knowledge_base WHERE id > 2", "DELETE FROM app_user"}, executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD)
class KnowledgeBaseApiTests {
    @LocalServerPort private int port;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.aiknowledge.service.UserService users;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.aiknowledge.service.LoginService login;
    private String token;

    @org.junit.jupiter.api.BeforeEach
    void authenticate() {
        String name = "kb_" + UUID.randomUUID().toString().substring(0, 8);
        users.register(name, "test-only-password");
        token = login.login(name, "test-only-password").accessToken();
    }

    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
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

    @Test void concurrentDuplicateCreatesOnlyOneRow() throws Exception {
        String name = "并发-" + UUID.randomUUID();
        String body = "{\"name\":\"" + name + "\"}";
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> create = () -> {
                start.await();
                return request("POST", "/api/knowledge-bases", body).statusCode();
            };
            var first = pool.submit(create);
            var second = pool.submit(create);
            start.countDown();
            var statuses = java.util.List.of(first.get(), second.get()).stream().sorted().toList();
            assertEquals(java.util.List.of(201, 409), statuses);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void updatesAndDeletesWithoutChangingManagedFields() throws Exception {
        var created = request("POST", "/api/knowledge-bases", "{\"name\":\"crud-" + UUID.randomUUID() + "\"}");
        String location = created.headers().firstValue("Location").orElseThrow();
        var original = json.readTree(created.body());
        String body = "{\"name\":\"  edited-" + UUID.randomUUID() + "  \",\"description\":\"  新说明  \"}";
        var updated = request("PUT", location, body);
        assertEquals(200, updated.statusCode());
        var record = json.readTree(updated.body());
        assertEquals(original.get("id"), record.get("id"));
        assertEquals(original.get("documentCount"), record.get("documentCount"));
        assertEquals(original.get("category"), record.get("category"));
        assertEquals("新说明", record.get("description").asText());
        assertEquals(record, json.readTree(request("GET", location, null).body()));
        assertEquals(200, request("PUT", location, body).statusCode());
        for (String invalid : new String[]{"{}", "null", "bad-json",
                "{\"name\":\"   \"}", "{\"name\":\"" + "x".repeat(61) + "\"}",
                "{\"name\":\"ok\",\"description\":\"" + "x".repeat(301) + "\"}"}) {
            assertEquals(400, request("PUT", location, invalid).statusCode());
        }
        assertEquals(409, request("PUT", location, "{\"name\":\"公司制度知识库\"}").statusCode());
        assertEquals(record, json.readTree(request("GET", location, null).body()));
        var deleted = request("DELETE", location, null);
        assertEquals(204, deleted.statusCode());
        assertEquals("", deleted.body());
        assertEquals(404, request("GET", location, null).statusCode());
        assertEquals(404, request("DELETE", location, null).statusCode());
        assertEquals(404, request("PUT", location, body).statusCode());
        assertEquals(400, request("DELETE", "/api/knowledge-bases/abc", null).statusCode());
    }

    @Test void anonymousAndInvalidTokensCannotReadOrWrite() throws Exception {
        String before = request("GET", "/api/knowledge-bases", null).body();
        for (String method : new String[]{"GET", "POST", "PUT", "DELETE"}) {
            String path = method.equals("POST") ? "/api/knowledge-bases" : "/api/knowledge-bases/1";
            for (String invalidToken : new String[]{"", "not-a-valid-jwt"}) {
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(method, method.equals("POST") || method.equals("PUT")
                                ? HttpRequest.BodyPublishers.ofString("{\"name\":\"unauthorized-write\"}")
                                : HttpRequest.BodyPublishers.noBody());
                if (!invalidToken.isEmpty()) builder.header("Authorization", "Bearer " + invalidToken);
                var response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(401, response.statusCode());
                assertTrue(json.readTree(response.body()).get("message").isTextual());
            }
        }
        assertEquals(before, request("GET", "/api/knowledge-bases", null).body());
        var list = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/knowledge-bases")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, list.statusCode());
    }
}
