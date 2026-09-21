package com.example.aiknowledge;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.io.IOException;
import com.example.aiknowledge.service.*;
import com.example.aiknowledge.mapper.UserAccessMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentApiTests {
    static final Path directory = temporaryDirectory();
    static Path temporaryDirectory() {
        try { return Files.createTempDirectory("lesson18-documents-test-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("app.documents.directory", directory::toString);
    }
    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired LoginService login;
    @Autowired UserAccessMapper access;
    @Autowired KnowledgeBaseService bases;
    @Autowired DocumentService documents;
    @Autowired com.example.aiknowledge.mapper.DocumentMapper documentMapper;
    @Autowired com.example.aiknowledge.mapper.KnowledgeBaseMapper baseMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    final JsonMapper json = JsonMapper.builder().build();
    long baseId;
    long userId;
    String token;
    final byte[] text = "# 学习笔记\n这是 UTF-8 文本。".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void setup() {
        String name = "doc_" + UUID.randomUUID().toString().substring(0,8);
        userId = users.register(name, "test-only-password").id();
        access.assignRole(userId, "ADMIN");
        token = login.login(name, "test-only-password").accessToken();
        baseId = bases.create(name, "上传测试").id();
    }
    @AfterEach void cleanup() throws IOException {
        jdbc.update("DELETE FROM document");
        jdbc.update("DELETE FROM knowledge_base WHERE id>2");
        jdbc.update("DELETE FROM app_user");
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) Files.delete(file);
        }
    }
    @AfterAll static void removeDirectory() throws IOException { Files.delete(directory); }

    HttpResponse<String> request(String method, String path, byte[] body, String contentType, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) builder.header("Content-Type", contentType);
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> upload(long id, String name, byte[] content, String bearer) throws Exception {
        String boundary = "lesson18Boundary";
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"knowledgeBaseId\"\r\n\r\n" + id
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        var body = new java.io.ByteArrayOutputStream(); body.write(head); body.write(content); body.write(tail);
        return request("POST", "/api/documents", body.toByteArray(), "multipart/form-data; boundary=" + boundary, bearer);
    }
    long fileCount() throws IOException { try (var files = Files.list(directory)) { return files.count(); } }

    @Test void uploadPreservesBytesListsByBaseAndCountsRealDocuments() throws Exception {
        long firstId = 0;
        for (int i=0; i<2; i++) {
            var response = upload(baseId, "notes.md", text, token);
            assertEquals(201, response.statusCode(), response.body());
            var info = json.readTree(response.body());
            assertEquals(7, info.size());
            assertFalse(info.has("objectKey"));
            assertEquals("UPLOADED", info.get("status").asText());
            assertEquals(text.length, info.get("fileSize").asLong());
            long id = info.get("id").asLong();
            assertNotEquals(firstId, id); firstId = id;
            String key = jdbc.queryForObject("SELECT object_key FROM document WHERE id=?", String.class, id);
            assertArrayEquals(text, Files.readAllBytes(directory.resolve(key)));
        }
        assertEquals(2, fileCount());
        var listed = request("GET", "/api/documents?knowledgeBaseId=" + baseId, null, null, token);
        assertEquals(200, listed.statusCode()); assertEquals(2, json.readTree(listed.body()).size());
        assertTrue(documents.list(1).isEmpty());
        assertEquals(2, bases.get(baseId).documentCount());
        assertEquals(2, bases.list().stream().filter(b -> b.id()==baseId).findFirst().orElseThrow().documentCount());
        assertEquals(409, request("DELETE", "/api/knowledge-bases/" + baseId, null, null, token).statusCode());
        assertEquals(2, fileCount());
    }

    @Test void anonymousAndReadonlyUserCannotUploadButEditorCanWithSameToken() throws Exception {
        assertEquals(401, request("GET", "/api/documents?knowledgeBaseId="+baseId, null, null, null).statusCode());
        assertEquals(401, upload(baseId,"notes.txt",text,null).statusCode());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",userId);
        access.assignRole(userId,"USER");
        assertEquals(200, request("GET", "/api/documents?knowledgeBaseId="+baseId,null,null,token).statusCode());
        assertEquals(403, upload(baseId,"notes.txt",text,token).statusCode());
        assertEquals(0,fileCount());
        access.assignRole(userId,"EDITOR");
        assertEquals(201,upload(baseId,"notes.txt",text,token).statusCode());
    }

    @Test void invalidFilesAndMissingFieldsLeaveNoMetadataOrFiles() throws Exception {
        assertEquals(400,upload(baseId,"empty.txt",new byte[0],token).statusCode());
        assertEquals(400,upload(baseId,"wrong.pdf",text,token).statusCode());
        assertEquals(400,upload(baseId,"binary.txt",new byte[]{0,1,2},token).statusCode());
        assertEquals(400,upload(baseId,"invalid.txt",new byte[]{(byte)0xff},token).statusCode());
        assertEquals(400,upload(baseId,"../escape.txt",text,token).statusCode());
        assertEquals(400,request("GET","/api/documents",null,null,token).statusCode());
        assertEquals(400,request("POST","/api/documents",("--b\r\nContent-Disposition: form-data; name=\"knowledgeBaseId\"\r\n\r\n"+baseId+"\r\n--b--\r\n").getBytes(StandardCharsets.UTF_8),"multipart/form-data; boundary=b",token).statusCode());
        assertEquals(0,fileCount()); assertTrue(documents.list(baseId).isEmpty());
    }

    @Test void sizeBoundaryIsEnforcedAndMissingBaseCreatesNoFile() throws Exception {
        assertEquals(404,upload(Long.MAX_VALUE,"notes.txt",text,token).statusCode());
        assertEquals(0,fileCount());
        byte[] bytes = new byte[DocumentService.MAX_BYTES + 1]; Arrays.fill(bytes,(byte)'a');
        var rejected = upload(baseId,"large.txt",bytes,token);
        assertEquals(413,rejected.statusCode(),rejected.body());
        assertTrue(json.readTree(rejected.body()).get("message").asText().contains("1 MB"));
        assertEquals(0,fileCount());
        assertEquals(201,upload(baseId,"limit.txt",Arrays.copyOf(bytes,DocumentService.MAX_BYTES),token).statusCode());
    }

    @Test void databaseRollbackRemovesNewFileAndStorageFailureDoesNotInsertRecord() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            documents.upload(baseId,new MockMultipartFile("file","rollback.md","text/markdown",text));
            tx.setRollbackOnly();
        });
        assertEquals(0,fileCount()); assertTrue(documents.list(baseId).isEmpty());
        Path blocked = Files.createTempFile(directory,"blocked-",".tmp");
        try {
            var storage = new LocalDocumentStorage(blocked.toString());
            var service = new DocumentService(documentMapper, baseMapper, storage);
            assertThrows(com.example.aiknowledge.exception.DocumentException.class, () ->
                new TransactionTemplate(transactions).execute(tx -> service.upload(baseId,
                        new MockMultipartFile("file","failed.txt","text/plain",text))));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document",Integer.class));
        } finally { Files.delete(blocked); }
    }
}
