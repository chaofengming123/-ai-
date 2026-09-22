package com.example.aiknowledge.controller;

import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.example.aiknowledge.service.VectorLabService;

@RestController
@RequestMapping("/api/embeddings/stored")
public class VectorLabController {
    private final VectorLabService lab;
    public VectorLabController(VectorLabService lab) { this.lab=lab; }
    public record Save(List<String> texts) {}
    public record Search(String query) {}
    @GetMapping
    public ResponseEntity<VectorLabService.Status> status(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lab.status(Long.parseLong(jwt.getSubject())));
    }
    @PostMapping
    public ResponseEntity<VectorLabService.Status> save(@AuthenticationPrincipal Jwt jwt,@RequestBody Save request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lab.save(Long.parseLong(jwt.getSubject()),request.texts()));
    }
    @PostMapping("/search")
    public ResponseEntity<VectorLabService.Results> search(@AuthenticationPrincipal Jwt jwt,@RequestBody Search request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lab.search(Long.parseLong(jwt.getSubject()),request.query()));
    }
}
