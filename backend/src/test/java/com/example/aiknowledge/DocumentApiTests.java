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
    @Test void chunkPreviewValidatesOptionsProtectsAccessAndPreservesSource() throws Exception {
        byte[] original="分块正文。\n".repeat(200).getBytes(StandardCharsets.UTF_8);
        var doc=documents.upload(baseId,new MockMultipartFile("file","chunks.md","text/plain",original));
        String path="/api/documents/"+doc.id()+"/chunks";
        assertEquals(401,request("GET",path,null,null,null).statusCode());
        var result=request("GET",path+"?size=300&overlap=50",null,null,token);
        assertEquals(200,result.statusCode());
        var data=json.readTree(result.body());
        assertEquals(300,data.get("chunkSize").asInt());
        assertTrue(data.get("chunks").size()>1);
        assertEquals("no-store",result.headers().firstValue("cache-control").orElseThrow());
        assertEquals(400,request("GET",path+"?size=200&overlap=100",null,null,token).statusCode());
        assertEquals(400,request("GET",path+"?size=oops",null,null,token).statusCode());
        assertEquals(404,request("GET","/api/documents/9223372036854775807/chunks",null,null,token).statusCode());
        assertArrayEquals(original,documents.download(doc.id()).bytes());
        assertEquals("UPLOADED",documentMapper.findById(doc.id()).status());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",userId);
        assertEquals(403,request("GET",path,null,null,token).statusCode());
    }
    @Test void chunkPreviewExplicitlyReportsTruncatedSource() {
        var doc=documents.upload(baseId,new MockMultipartFile("file","large.txt","text/plain","字".repeat(40001).getBytes(StandardCharsets.UTF_8)));
        var result=documents.chunks(doc.id(),500,50);
        assertTrue(result.sourceTruncated()); assertEquals(40000,result.sourceCharacters());
        assertEquals(40000,result.chunks().get(result.chunks().size()-1).endOffset());
    }
    @Test void previewIsAuthenticatedReadOnlyAndReturns404ForMissingDocuments() throws Exception {
        var doc=documents.upload(baseId,new MockMultipartFile("file","note.md","text/plain",text));
        String path="/api/documents/"+doc.id()+"/text";
        assertEquals(401,request("GET",path,null,null,null).statusCode());
        var result=request("GET",path,null,null,token);
        assertEquals(200,result.statusCode());
        assertEquals(new String(text,StandardCharsets.UTF_8),json.readTree(result.body()).get("content").asText());
        assertEquals("no-store",result.headers().firstValue("cache-control").orElseThrow());
        assertArrayEquals(text,documents.download(doc.id()).bytes());
        assertEquals("UPLOADED",documentMapper.findById(doc.id()).status());
        assertEquals(404,request("GET","/api/documents/9223372036854775807/text",null,null,token).statusCode());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",userId);
        assertEquals(403,request("GET",path,null,null,token).statusCode());
    }

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

    @Test void legacyLocalDownloadRequiresReadPermissionAndReturnsAttachment() throws Exception {
        var created=upload(baseId,"notes.md",text,token);
        long id=json.readTree(created.body()).get("id").asLong();
        String path="/api/documents/"+id+"/download";
        assertEquals(401,request("GET",path,null,null,null).statusCode());
        var response=request("GET",path,null,null,token);
        assertEquals(200,response.statusCode());
        assertArrayEquals(text,response.body().getBytes(StandardCharsets.UTF_8));
        assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment;"));
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("nosniff",response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals(404,request("GET","/api/documents/9223372036854775807/download",null,null,token).statusCode());
        jdbc.update("DELETE FROM app_user_role WHERE user_id=?",userId);
        assertEquals(403,request("GET",path,null,null,token).statusCode());
    }

    @Test void invalidFilesAndMissingFieldsLeaveNoMetadataOrFiles() throws Exception {
        assertEquals(400,upload(baseId,"empty.txt",new byte[0],token).statusCode());
        assertEquals(400,upload(baseId,"wrong.pdf",text,token).statusCode());
        assertEquals(400,upload(baseId,"wrong.docx",text,token).statusCode());
        assertEquals(400,upload(baseId,"wrong.doc",text,token).statusCode());
        assertEquals(400,upload(baseId,"encrypted.pdf",DocumentFixtures.pdf(1,true),token).statusCode());
        assertEquals(400,upload(baseId,"binary.txt",new byte[]{0,1,2},token).statusCode());
        assertEquals(400,upload(baseId,"invalid.txt",new byte[]{(byte)0xff},token).statusCode());
        assertEquals(400,upload(baseId,"../escape.txt",text,token).statusCode());
        assertEquals(400,request("GET","/api/documents",null,null,token).statusCode());
        assertEquals(400,request("POST","/api/documents",("--b\r\nContent-Disposition: form-data; name=\"knowledgeBaseId\"\r\n\r\n"+baseId+"\r\n--b--\r\n").getBytes(StandardCharsets.UTF_8),"multipart/form-data; boundary=b",token).statusCode());
        assertEquals(0,fileCount()); assertTrue(documents.list(baseId).isEmpty());
    }

    @Test void binaryFormatsUploadAndDownloadByteForByte() throws Exception {
        for(String type:List.of("pdf","docx","doc")) {
            byte[] bytes=type.equals("pdf")?DocumentFixtures.pdf(1,false):type.equals("doc")?LegacyWordTests.fixture():DocumentFixtures.docx();
            var response=upload(baseId,"lesson."+type.toUpperCase(Locale.ROOT),bytes,token);
            assertEquals(201,response.statusCode(),response.body());
            var info=json.readTree(response.body());
            assertEquals(type,info.get("fileType").asText());
            assertEquals("UPLOADED",info.get("status").asText());
            var downloaded=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/documents/"+info.get("id").asLong()+"/download"))
                    .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,downloaded.statusCode()); assertArrayEquals(bytes,downloaded.body());
            if(type.equals("doc")) {
                var preview=request("GET","/api/documents/"+info.get("id").asLong()+"/text",null,null,token);
                assertEquals(200,preview.statusCode(),preview.body());
                assertTrue(json.readTree(preview.body()).path("content").asText().contains("This is a simple file"));
            }
        }
    }

    @Test void sizeBoundaryIsEnforcedAndMissingBaseCreatesNoFile() throws Exception {
        assertEquals(5 * 1024 * 1024, DocumentService.MAX_BYTES);
        assertEquals(404,upload(Long.MAX_VALUE,"notes.txt",text,token).statusCode());
        assertEquals(0,fileCount());
        byte[] bytes = new byte[DocumentService.MAX_BYTES + 1]; Arrays.fill(bytes,(byte)'a');
        var rejected = upload(baseId,"large.txt",bytes,token);
        assertEquals(413,rejected.statusCode(),rejected.body());
        assertTrue(json.readTree(rejected.body()).get("message").asText().contains("5 MB"));
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
            var service = new DocumentService(documentMapper, baseMapper, new DocumentStorage(storage,Optional.empty(),"local"));
            assertThrows(com.example.aiknowledge.exception.DocumentException.class, () ->
                new TransactionTemplate(transactions).execute(tx -> service.upload(baseId,
                        new MockMultipartFile("file","failed.txt","text/plain",text))));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document",Integer.class));
        } finally { Files.delete(blocked); }
    }
}
