package com.example.aiknowledge;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import com.example.aiknowledge.service.UserService;
import com.example.aiknowledge.service.LoginService;
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
@org.springframework.test.context.jdbc.Sql(
    statements = {"DELETE FROM knowledge_base WHERE id > 2", "DELETE FROM app_user"},
    executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD)
class RoleApiTests {
    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired LoginService login;
    @Autowired UserMapper mapper;
    @Autowired JwtEncoder encoder;
    final JsonMapper json = JsonMapper.builder().build();

    HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void roleChangesApplyToSameTokenAndDeniedWritesLeaveDataIntact() throws Exception {
        String name = "role_" + UUID.randomUUID().toString().substring(0, 8);
        var user = users.register(name, "test-only-password");
        String token = login.login(name, "test-only-password").accessToken();
        String base = "/api/knowledge-bases";
        var before = request("GET", base, null, token);
        assertEquals(200, before.statusCode());
        assertEquals(200, request("GET", base + "/1", null, token).statusCode());
        for (String method : new String[]{"POST", "PUT", "DELETE"}) {
            var response = request(method, method.equals("POST") ? base : base + "/1",
                    method.equals("DELETE") ? null : "{\"name\":\"forbidden\"}", token);
            assertEquals(403, response.statusCode());
            assertTrue(json.readTree(response.body()).get("message").asText().contains("权限"));
        }
        assertEquals(before.body(), request("GET", base, null, token).body());
        var entity = mapper.selectById(user.id());
        entity.setRole("ADMIN");
        mapper.updateById(entity);
        assertEquals("ADMIN", json.readTree(request("GET", "/api/auth/me", null, token).body()).get("role").asText());
        var created = request("POST", base, "{\"name\":\"role-test\"}", token);
        assertEquals(201, created.statusCode());
        String path = base + "/" + json.readTree(created.body()).get("id").asLong();
        assertEquals(200, request("PUT", path, "{\"name\":\"role-updated\"}", token).statusCode());

        entity.setRole("USER");
        mapper.updateById(entity);
        assertEquals(403, request("DELETE", path, null, token).statusCode());
        assertEquals(200, request("GET", path, null, token).statusCode());
        assertEquals("USER", json.readTree(request("GET", "/api/auth/me", null, token).body()).get("role").asText());
        entity.setRole("ADMIN");
        mapper.updateById(entity);
        assertEquals(204, request("DELETE", path, null, token).statusCode());
    }

    @Test void registrationAndJwtRoleClaimsCannotGrantAdminRights() throws Exception {
        String name = "new_" + UUID.randomUUID().toString().substring(0, 8);
        var response = request("POST", "/api/auth/register",
                "{\"username\":\"" + name + "\",\"password\":\"test-only-password\",\"role\":\"ADMIN\"}", null);
        assertEquals(201, response.statusCode());
        var data = json.readTree(response.body());
        assertEquals("USER", data.get("role").asText());
        long id = data.get("id").asLong();
        assertEquals("USER", mapper.selectById(id).getRole());
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(Long.toString(id)).issuer(TokenConfig.ISSUER)
                .issuedAt(now).expiresAt(now.plusSeconds(900))
                .claim("role", "ADMIN").claim("scope", "ADMIN").build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        assertEquals(403, request("POST", "/api/knowledge-bases", "{\"name\":\"cannot-escalate\"}", token).statusCode());
    }
}
