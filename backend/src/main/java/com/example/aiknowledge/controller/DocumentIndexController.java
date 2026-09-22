package com.example.aiknowledge.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.example.aiknowledge.service.DocumentIndexService;

@RestController
@RequestMapping("/api/documents/{id}/index")
public class DocumentIndexController {
    private final DocumentIndexService indexes;
    public DocumentIndexController(DocumentIndexService indexes) { this.indexes=indexes; }
    @GetMapping
    public ResponseEntity<DocumentIndexService.Status> status(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(indexes.status(id));
    }
    @PostMapping
    public ResponseEntity<DocumentIndexService.Status> build(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(indexes.build(id));
    }
}
