package com.example.aiknowledge.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.example.aiknowledge.service.DocumentSearchService;

@RestController
@RequestMapping("/api/documents/{id}/search")
public class DocumentSearchController {
    private final DocumentSearchService search;
    public DocumentSearchController(DocumentSearchService search) { this.search=search; }
    public record Query(String query) {}
    @PostMapping
    public ResponseEntity<DocumentSearchService.Result> search(@PathVariable long id,@RequestBody Query request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(id,request.query()));
    }
}
