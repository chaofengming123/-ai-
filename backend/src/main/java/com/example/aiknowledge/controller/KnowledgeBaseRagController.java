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
    public record Query(String query,String mode,java.util.List<String> keywords,Boolean rerank,Boolean bypassCache) {}
    @PostMapping("/search")
    public ResponseEntity<Object> search(@PathVariable long id,@RequestBody Query query,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rag.execute(Long.parseLong(jwt.getSubject()),id,query.query(),false,query.mode(),query.keywords(),Boolean.TRUE.equals(query.rerank()),Boolean.TRUE.equals(query.bypassCache())));
    }
    @PostMapping("/answer")
    public ResponseEntity<Object> answer(@PathVariable long id,@RequestBody Query query,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rag.execute(Long.parseLong(jwt.getSubject()),id,query.query(),true,query.mode(),query.keywords(),Boolean.TRUE.equals(query.rerank()),Boolean.TRUE.equals(query.bypassCache())));
    }
}
