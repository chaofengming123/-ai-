package com.example.aiknowledge.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.aiknowledge.dto.CreateKnowledgeBaseRequest;
import com.example.aiknowledge.model.KnowledgeBase;
import com.example.aiknowledge.service.KnowledgeBaseService;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @GetMapping
    public List<KnowledgeBase> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public KnowledgeBase get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<KnowledgeBase> create(@RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBase created = service.create(request.name(), request.description());
        return ResponseEntity.created(URI.create("/api/knowledge-bases/" + created.id())).body(created);
    }
}
