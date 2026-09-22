package com.example.aiknowledge;

import com.example.aiknowledge.mapper.*;
import com.example.aiknowledge.model.*;
import com.example.aiknowledge.service.*;
import com.example.aiknowledge.exception.DocumentException;
import io.minio.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="app.documents.backend=minio")
class MinioDocumentTests {
    static final String bucket="course-test-"+UUID.randomUUID();
    static final Path directory=tempDirectory();
    static Path tempDirectory() {
        try { return Files.createTempDirectory("lesson19-local-test-"); }
        catch(IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry properties) {
        properties.add("app.minio.bucket",()->bucket);
        properties.add("app.documents.directory",directory::toString);
    }
    static MinioClient client() {
        return MinioClient.builder().endpoint("http://127.0.0.1:9000")
                .credentials(System.getenv("MINIO_ROOT_USER"),System.getenv("MINIO_ROOT_PASSWORD")).build();
    }
    @Autowired DocumentService documents;
    @Autowired DocumentMigrationService migration;
    @Autowired DocumentStorage storage;
    @Autowired LocalDocumentStorage local;
    @Autowired MinioDocumentStorage minio;
    @Autowired DocumentMapper mapper;
    @Autowired KnowledgeBaseMapper baseMapper;
    @Autowired KnowledgeBaseService bases;
    @Autowired UserService users;
    @Autowired LoginService login;
    @Autowired UserAccessMapper access;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    long baseId;
    String token;
    final byte[] content="# MinIO 笔记\n保存原始内容".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile file() { return new MockMultipartFile("file","notes.md","text/markdown",content); }
    @Test void previewReadsPrivateMinioObjectWithoutChangingIt() throws Exception {
        byte[] original=DocumentFixtures.docx();
        var info=documents.upload(baseId,new MockMultipartFile("file","preview.docx","application/octet-stream",original));
        var preview=documents.preview(info.id());
        assertTrue(preview.content().contains("第二十课：原文件保持不变。"));
        assertFalse(preview.truncated());
        assertArrayEquals(original,documents.download(info.id()).bytes());
        assertEquals("UPLOADED",mapper.findById(info.id()).status());
    }

    @BeforeEach void setup() {
        String name="minio_"+UUID.randomUUID().toString().substring(0,8);
        var user=users.register(name,"test-only-password");
        access.assignRole(user.id(),"EDITOR");
        token=login.login(name,"test-only-password").accessToken();
        baseId=bases.create(name,"MinIO test").id();
    }
    @AfterEach void cleanup() throws Exception {
        jdbc.update("DELETE FROM document");
        jdbc.update("DELETE FROM knowledge_base WHERE id>2");
        jdbc.update("DELETE FROM app_user");
        var client=client();
        if(client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            for(var result:client.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build()))
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(result.get().objectName()).build());
        }
        try(var files=Files.list(directory)) { for(var path:files.toList()) Files.delete(path); }
    }
    @AfterAll static void finish() throws Exception {
        var client=client();
        if(client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
            client.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
        Files.delete(directory);
    }
    long objectCount() throws Exception {
        var client=client();
        if(!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) return 0;
        long count=0;
        for(var result:client.listObjects(ListObjectsArgs.builder().bucket(bucket).build())) { result.get(); count++; }
        return count;
    }
    DocumentInfo legacy() {
        String key=local.save(content);
        // 不填新字段，模拟 V5 旧记录的默认定位方式。
        jdbc.update("INSERT INTO document(knowledge_base_id,file_name,object_key,file_type,file_size) VALUES(?,?,?,?,?)",baseId,"old.md",key,"md",content.length);
        return mapper.findByKey(key);
    }
    @Test void actualMultipartUploadStoresPrivateObjectAndDownloadPreservesBytes() throws Exception {
        String boundary="minioBoundary";
        String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"knowledgeBaseId\"\r\n\r\n"+baseId+
                "\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"notes.md\"\r\nContent-Type: text/markdown\r\n\r\n"+
                new String(content,StandardCharsets.UTF_8)+"\r\n--"+boundary+"--\r\n";
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/documents"))
                .timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token)
                .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(201,response.statusCode(),response.body());
        assertFalse(response.body().contains("storageBucket"));
        assertFalse(response.body().contains("objectKey"));
        var doc=documents.list(baseId).get(0);
        var object=mapper.object(doc.id());
        assertEquals("MINIO",object.storageBackend()); assertEquals(bucket,object.storageBucket());
        assertArrayEquals(content,storage.read(object.location()));
        assertThrows(DocumentException.class,()->minio.save(object.objectKey(),new byte[]{42}));
        assertArrayEquals(content,storage.read(object.location()));
        assertEquals(1,objectCount());
        try(var files=Files.list(directory)) { assertEquals(0,files.count()); }
        var anonymous=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:9000/"+bucket+"/"+object.objectKey())).GET().build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(403,anonymous.statusCode());
        var downloaded=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/documents/"+doc.id()+"/download"))
                .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,downloaded.statusCode()); assertArrayEquals(content,downloaded.body());
    }
    @Test void rollbackRemovesObjectAndUnavailableMinioCreatesNoRecord() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            documents.upload(baseId,file()); tx.setRollbackOnly();
        });
        assertEquals(0,objectCount()); assertTrue(documents.list(baseId).isEmpty());
        var unavailable=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        unavailable.createContext("/",exchange->{exchange.sendResponseHeaders(503,-1);exchange.close();});
        unavailable.start();
        try {
            var failed=new MinioDocumentStorage("http://127.0.0.1:"+unavailable.getAddress().getPort(),"test-key","test-only-secret",bucket);
            var router=new DocumentStorage(local,Optional.of(failed),"minio");
            var service=new DocumentService(mapper,baseMapper,router);
            assertThrows(DocumentException.class,()->new TransactionTemplate(transactions).execute(tx->service.upload(baseId,file())));
            assertTrue(documents.list(baseId).isEmpty()); assertEquals(0,objectCount());
        } finally { unavailable.stop(0); }
    }

    @Test void pdfAndDocxKeepOriginalBytesInMinio() throws Exception {
        for(String type:List.of("pdf","docx")) {
            byte[] bytes=type.equals("pdf")?DocumentFixtures.pdf(1,false):DocumentFixtures.docx();
            var info=documents.upload(baseId,new MockMultipartFile("file","lesson."+type,"application/octet-stream",bytes));
            assertEquals(type,info.fileType());
            assertEquals("MINIO",mapper.object(info.id()).storageBackend());
            assertArrayEquals(bytes,documents.download(info.id()).bytes());
        }
        assertEquals(2,objectCount());
        assertThrows(DocumentException.class,()->documents.upload(baseId,new MockMultipartFile("file","fake.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",content)));
        assertEquals(2,objectCount()); assertEquals(2,documents.list(baseId).size());
    }
    @Test void migrationPreviewAndApplyKeepIdentityAndLocalBackupAndAreRepeatable() throws Exception {
        var old=legacy(); var location=mapper.object(old.id()).location();
        assertTrue(migration.migrate(old.id(),false));
        assertEquals("LOCAL",mapper.object(old.id()).storageBackend()); assertEquals(0,objectCount());
        assertArrayEquals(content,documents.download(old.id()).bytes());
        assertTrue(migration.migrate(old.id(),true));
        assertEquals("MINIO",mapper.object(old.id()).storageBackend());
        assertEquals(old,mapper.findById(old.id()));
        assertArrayEquals(content,documents.download(old.id()).bytes());
        assertArrayEquals(content,local.read(location.key()));
        assertFalse(migration.migrate(old.id(),true)); assertEquals(1,objectCount());
    }
    @Test void missingSourceAndReadbackMismatchDoNotSwitchMetadata() throws Exception {
        var old=legacy(); var source=mapper.object(old.id()).location();
        var spy=spy(storage);
        doReturn(new byte[]{1,2}).when(spy).read(argThat(file->file!=null && file.backend().equals("MINIO")));
        var service=new DocumentMigrationService(mapper,spy);
        assertThrows(DocumentException.class,()->new TransactionTemplate(transactions).execute(tx->service.migrate(old.id(),true)));
        assertEquals("LOCAL",mapper.object(old.id()).storageBackend()); assertEquals(0,objectCount());
        Files.delete(directory.resolve(source.key()));
        assertThrows(DocumentException.class,()->migration.migrate(old.id(),true));
        assertEquals("LOCAL",mapper.object(old.id()).storageBackend()); assertEquals(0,objectCount());
    }
}
