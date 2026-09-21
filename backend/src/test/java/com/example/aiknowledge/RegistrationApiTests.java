package com.example.aiknowledge;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.aiknowledge.entity.UserEntity;
import com.example.aiknowledge.mapper.UserMapper;
import com.example.aiknowledge.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.jdbc.Sql(statements = "DELETE FROM app_user", executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD)
class RegistrationApiTests {
    @LocalServerPort private int port;
    @Autowired private UserMapper mapper;
    @Autowired private PasswordEncoder encoder;
    private final JsonMapper json = JsonMapper.builder().build();

    private String username() { return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }
    private HttpResponse<String> post(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/register"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> register(String username, String password) throws Exception {
        return post(json.writeValueAsString(Map.of("username", username, "password", password)));
    }

    @Test void storesOnlySaltedHashesAndReturnsPublicFields() throws Exception {
        String username = username();
        String password = "  test-only-password  ";
        var first = register("  " + username.toUpperCase(java.util.Locale.ROOT) + "  ", password);
        assertEquals(201, first.statusCode());
        var response = json.readTree(first.body());
        assertEquals(3, response.size());
        assertEquals("USER", response.get("role").asText());
        assertEquals(username, response.get("username").asText());
        assertFalse(first.body().contains("password"));
        UserEntity stored = mapper.selectById(response.get("id").asLong());
        assertNotNull(stored);
        assertNotEquals(password, stored.getPasswordHash());
        assertTrue(encoder.matches(password, stored.getPasswordHash()));
        assertFalse(encoder.matches(password.strip(), stored.getPasswordHash()));
        assertFalse(encoder.matches("wrong-password", stored.getPasswordHash()));
        assertFalse(json.writeValueAsString(stored).contains(stored.getPasswordHash()));
        var second = register(username(), password);
        assertEquals(201, second.statusCode());
        UserEntity another = mapper.selectById(json.readTree(second.body()).get("id").asLong());
        assertNotEquals(stored.getPasswordHash(), another.getPasswordHash());
        assertTrue(encoder.matches(password, another.getPasswordHash()));
        assertFalse(new RegisterRequest(username, password).toString().contains(password));
    }

    @Test void rejectsInvalidInputWithoutCreatingUsers() throws Exception {
        long before = mapper.selectCount(null);
        for (String body : new String[]{"{}", "null", "bad-json",
                "{\"username\":\"ab\",\"password\":\"test-only-password\"}",
                "{\"username\":\"中文名\",\"password\":\"test-only-password\"}",
                "{\"username\":\"valid\",\"password\":null}"}) {
            assertEquals(400, post(body).statusCode());
        }
        for (String password : new String[]{"short", " ".repeat(12), "a".repeat(73), "中".repeat(25)}) {
            var response = register(username(), password);
            assertEquals(400, response.statusCode());
            assertFalse(response.body().contains(password));
        }
        assertEquals(before, mapper.selectCount(null));
    }

    @Test void acceptsExactByteLimitAndPreservesOriginalOnDuplicate() throws Exception {
        String username = username();
        String password = "中".repeat(24);
        var response = register(username, password);
        assertEquals(201, response.statusCode());
        long id = json.readTree(response.body()).get("id").asLong();
        String hash = mapper.selectById(id).getPasswordHash();
        assertTrue(encoder.matches(password, hash));
        var duplicate = register(username.toUpperCase(java.util.Locale.ROOT), "another-test-password");
        assertEquals(409, duplicate.statusCode());
        assertEquals(hash, mapper.selectById(id).getPasswordHash());
    }

    @Test void concurrentSameUsernameCreatesOnlyOneAccount() throws Exception {
        String username = username();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> create = () -> {
                start.await();
                return register(username, "test-only-password").statusCode();
            };
            var first = pool.submit(create);
            var second = pool.submit(create);
            start.countDown();
            assertEquals(java.util.List.of(201, 409),
                    java.util.List.of(first.get(), second.get()).stream().sorted().toList());
            assertEquals(1, mapper.selectCount(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username)));
        } finally {
            pool.shutdownNow();
        }
    }
}
