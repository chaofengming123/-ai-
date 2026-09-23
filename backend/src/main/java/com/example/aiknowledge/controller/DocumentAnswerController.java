package com.example.aiknowledge.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.example.aiknowledge.service.DocumentAnswerService;

@RestController
@RequestMapping("/api/documents/{id}/answer")
public class DocumentAnswerController {
    private final DocumentAnswerService answers;
    public DocumentAnswerController(DocumentAnswerService answers) { this.answers=answers; }
    public record Question(String query) {}
    @PostMapping
    public ResponseEntity<DocumentAnswerService.Answer> answer(@PathVariable long id,@RequestBody Question question,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(answers.answer(Long.parseLong(jwt.getSubject()),id,question.query()));
    }
}
