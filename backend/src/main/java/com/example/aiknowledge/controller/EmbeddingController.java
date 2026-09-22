package com.example.aiknowledge.controller;

import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.example.aiknowledge.service.*;

@RestController
@RequestMapping("/api/embeddings")
public class EmbeddingController {
    private final EmbeddingService service;
    private final EmbeddingClient client;
    public EmbeddingController(EmbeddingService service,EmbeddingClient client) { this.service=service; this.client=client; }
    public record Request(String query,List<String> candidates) {}
    @GetMapping("/config")
    public ResponseEntity<EmbeddingClient.Configuration> config() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(client.configuration());
    }
    @PostMapping("/compare")
    public ResponseEntity<EmbeddingService.Comparison> compare(@RequestBody Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.compare(request.query(),request.candidates()));
    }
}
