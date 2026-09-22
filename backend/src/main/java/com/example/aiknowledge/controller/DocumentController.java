package com.example.aiknowledge.controller;

import java.util.List;
import com.example.aiknowledge.model.DocumentInfo;
import com.example.aiknowledge.service.DocumentService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService documents;
    public DocumentController(DocumentService documents) { this.documents = documents; }
    @GetMapping
    public List<DocumentInfo> list(@RequestParam long knowledgeBaseId) {
        return documents.list(knowledgeBaseId);
    }
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        var file=documents.download(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.bytes().length).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                        .filename(file.name(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }
    @GetMapping("/{id}/text")
    public ResponseEntity<DocumentService.Preview> text(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(documents.preview(id));
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentInfo> upload(@RequestParam long knowledgeBaseId,
                                               @RequestParam MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documents.upload(knowledgeBaseId, file));
    }
}
