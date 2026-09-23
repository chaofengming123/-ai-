package com.example.aiknowledge.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.example.aiknowledge.service.KnowledgeBaseRagService;

@RestController
@RequestMapping("/api/knowledge-bases/{id}")
public class KnowledgeBaseRagController {
    private final KnowledgeBaseRagService rag;
    public KnowledgeBaseRagController(KnowledgeBaseRagService rag) { this.rag=rag; }
    public record Query(String query) {}
    @PostMapping("/search")
    public ResponseEntity<Object> search(@PathVariable long id,@RequestBody Query query,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rag.execute(Long.parseLong(jwt.getSubject()),id,query.query(),false));
    }
    @PostMapping("/answer")
    public ResponseEntity<Object> answer(@PathVariable long id,@RequestBody Query query,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rag.execute(Long.parseLong(jwt.getSubject()),id,query.query(),true));
    }
}
