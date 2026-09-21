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
    @Autowired com.example.aiknowledge.mapper.UserAccessMapper access;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    void setRole(long id, String role) {
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
        access.assignRole(id, role);
    }
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
        setRole(user.id(), "ADMIN");
        assertEquals("ADMIN", json.readTree(request("GET", "/api/auth/me", null, token).body()).get("roles").get(0).asText());
        var created = request("POST", base, "{\"name\":\"role-test\"}", token);
        assertEquals(201, created.statusCode());
        String path = base + "/" + json.readTree(created.body()).get("id").asLong();
        assertEquals(200, request("PUT", path, "{\"name\":\"role-updated\"}", token).statusCode());

        setRole(user.id(), "USER");
        assertEquals(403, request("DELETE", path, null, token).statusCode());
        assertEquals(200, request("GET", path, null, token).statusCode());
        assertEquals("USER", json.readTree(request("GET", "/api/auth/me", null, token).body()).get("roles").get(0).asText());
        setRole(user.id(), "ADMIN");
        assertEquals(204, request("DELETE", path, null, token).statusCode());
    }

    @Test void registrationAndJwtRoleClaimsCannotGrantAdminRights() throws Exception {
        String name = "new_" + UUID.randomUUID().toString().substring(0, 8);
        var response = request("POST", "/api/auth/register",
                "{\"username\":\"" + name + "\",\"password\":\"test-only-password\",\"role\":\"ADMIN\"}", null);
        assertEquals(201, response.statusCode());
        var data = json.readTree(response.body());
        assertEquals("USER", data.get("roles").get(0).asText());
        long id = data.get("id").asLong();
        assertEquals(List.of("USER"), access.roles(id));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(Long.toString(id)).issuer(TokenConfig.ISSUER)
                .issuedAt(now).expiresAt(now.plusSeconds(900))
                .claim("role", "ADMIN").claim("scope", "ADMIN").build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        assertEquals(403, request("POST", "/api/knowledge-bases", "{\"name\":\"cannot-escalate\"}", token).statusCode());
    }
    @Test void multipleRolesUnionPermissionsAndEditorCannotDelete() throws Exception {
        var user = users.register("editor_" + UUID.randomUUID().toString().substring(0,8), "test-only-password");
        access.assignRole(user.id(), "EDITOR");
        assertEquals(List.of("EDITOR", "USER"), access.roles(user.id()));
        assertEquals(3, access.permissions(user.id()).stream().filter(p -> p.startsWith("knowledge-base:")).count());
        String token = login.login(user.username(), "test-only-password").accessToken();
        var created = request("POST", "/api/knowledge-bases", "{\"name\":\"editor-test\"}", token);
        assertEquals(201, created.statusCode());
        String path = "/api/knowledge-bases/" + json.readTree(created.body()).get("id").asLong();
        assertEquals(200, request("PUT", path, "{\"name\":\"editor-updated\"}", token).statusCode());
        assertEquals(403, request("DELETE", path, null, token).statusCode());
        assertEquals(200, request("GET", path, null, token).statusCode());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?", user.id());
        assertEquals(403, request("GET", path, null, token).statusCode());
        assertEquals(200, request("GET", "/api/auth/me", null, token).statusCode());
    }

    @Test void permissionMappingChangeAppliesWithoutNewToken() throws Exception {
        var user = users.register("perm_" + UUID.randomUUID().toString().substring(0,8), "test-only-password");
        setRole(user.id(), "EDITOR");
        String token = login.login(user.username(), "test-only-password").accessToken();
        try {
            jdbc.update("DELETE rp FROM app_role_permission rp JOIN app_role r ON r.id=rp.role_id JOIN app_permission p ON p.id=rp.permission_id WHERE r.code='EDITOR' AND p.code='knowledge-base:create'");
            assertEquals(403, request("POST", "/api/knowledge-bases", "{\"name\":\"blocked\"}", token).statusCode());
            assertEquals(200, request("GET", "/api/knowledge-bases", null, token).statusCode());
        } finally {
            jdbc.update("INSERT INTO app_role_permission SELECT r.id,p.id FROM app_role r CROSS JOIN app_permission p WHERE r.code='EDITOR' AND p.code='knowledge-base:create'");
        }
        assertEquals(201, request("POST", "/api/knowledge-bases", "{\"name\":\"restored\"}", token).statusCode());
    }

}
