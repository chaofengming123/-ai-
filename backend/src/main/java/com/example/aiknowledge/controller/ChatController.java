package com.example.aiknowledge.controller;

import java.util.List;
import com.example.aiknowledge.model.ChatMessage;
import com.example.aiknowledge.service.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chat;
    public ChatController(ChatService chat) { this.chat=chat; }
    public record ChatRequest(List<ChatMessage> messages) {}
    @GetMapping("/config")
    public ResponseEntity<LlmClient.Configuration> config() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.configuration());
    }
    @PostMapping
    public ResponseEntity<LlmClient.Reply> send(@RequestBody ChatRequest request,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.send(Long.parseLong(jwt.getSubject()),request.messages()));
    }
}
