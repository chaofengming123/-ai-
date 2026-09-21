package com.example.aiknowledge;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import com.example.aiknowledge.service.UserService;
import com.example.aiknowledge.mapper.UserMapper;
import com.example.aiknowledge.config.TokenConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.jdbc.Sql(statements = "DELETE FROM app_user",
        executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD)
class LoginApiTests {
    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired UserMapper mapper;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    final JsonMapper json = JsonMapper.builder().build();
    String uniqueName() { return "login_" + UUID.randomUUID().toString().substring(0, 8); }

    HttpResponse<String> request(String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> login(String name, String password) throws Exception {
        return request("/api/auth/login", json.writeValueAsString(Map.of("username", name, "password", password)), null);
    }
    String signed(String subject, String issuer, Instant expiration) {
        var claims = JwtClaimsSet.builder().subject(subject).issuer(issuer)
                .issuedAt(expiration.minusSeconds(900)).expiresAt(expiration).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test void loginIssuesTokenAndMeReadsIdentityFromIt() throws Exception {
        String name = uniqueName();
        var user = users.register(name, " test-only-password ");
        var response = login("  " + name.toUpperCase(Locale.ROOT) + "  ", " test-only-password ");
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(response.headers().firstValue("Set-Cookie").isPresent());
        var data = json.readTree(response.body());
        assertEquals("Bearer", data.get("tokenType").asText());
        assertEquals(900, data.get("expiresIn").asLong());
        assertFalse(response.body().contains("password"));
        String token = data.get("accessToken").asText();
        var jwt = decoder.decode(token);
        assertEquals(Long.toString(user.id()), jwt.getSubject());
        assertEquals(900, Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds());
        assertFalse(jwt.getClaims().containsKey("password"));
        var me = request("/api/auth/me", null, token);
        assertEquals(200, me.statusCode());
        assertEquals(user.id(), json.readTree(me.body()).get("id").asLong());
        assertEquals(3, json.readTree(me.body()).size());
        assertEquals("USER", json.readTree(me.body()).get("role").asText());
    }

    @Test void wrongPasswordAndMissingUserReturnSameError() throws Exception {
        String name = uniqueName();
        users.register(name, " test-only-password ");
        var wrong = login(name, "test-only-password");
        var absent = login(uniqueName(), "test-only-password");
        assertEquals(401, wrong.statusCode());
        assertEquals(401, absent.statusCode());
        assertEquals(wrong.body(), absent.body());
        assertEquals(401, login(name, "a".repeat(73)).statusCode());
        assertEquals(400, request("/api/auth/login", "null", null).statusCode());
        assertEquals(400, request("/api/auth/login", "not-json", null).statusCode());
    }

    @Test void protectedIdentityRejectsMissingMalformedTamperedExpiredAndWrongIssuerTokens() throws Exception {
        var user = users.register(uniqueName(), "test-only-password");
        assertEquals(401, request("/api/auth/me", null, null).statusCode());
        assertEquals(401, request("/api/auth/me", null, "not-a-token").statusCode());
        String valid = signed(Long.toString(user.id()), TokenConfig.ISSUER, Instant.now().plusSeconds(900));
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"999999\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[2];
        assertEquals(401, request("/api/auth/me", null, tampered).statusCode());
        assertEquals(401, request("/api/auth/me", null,
                signed(Long.toString(user.id()), TokenConfig.ISSUER, Instant.now().minusSeconds(120))).statusCode());
        assertEquals(401, request("/api/auth/me", null,
                signed(Long.toString(user.id()), "other-service", Instant.now().plusSeconds(900))).statusCode());
    }

    @Test void deletedUserCannotUseOtherwiseValidToken() throws Exception {
        var user = users.register(uniqueName(), "test-only-password");
        String token = signed(Long.toString(user.id()), TokenConfig.ISSUER, Instant.now().plusSeconds(900));
        assertEquals(200, request("/api/knowledge-bases", null, token).statusCode());
        mapper.deleteById(user.id());
        assertEquals(401, request("/api/auth/me", null, token).statusCode());
        assertEquals(401, request("/api/knowledge-bases", null, token).statusCode());
    }
}
