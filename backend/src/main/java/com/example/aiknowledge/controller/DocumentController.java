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
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentInfo> upload(@RequestParam long knowledgeBaseId,
                                               @RequestParam MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documents.upload(knowledgeBaseId, file));
    }
}
