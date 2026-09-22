package com.example.aiknowledge.controller;

import java.util.List;
import com.example.aiknowledge.model.ChatMessage;
import com.example.aiknowledge.service.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.Map;
import com.example.aiknowledge.exception.ChatException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chat;
    public ChatController(ChatService chat) { this.chat=chat; }
    public record ChatRequest(List<ChatMessage> messages) {}
    private final JsonMapper json=JsonMapper.builder().build();
    @GetMapping("/config")
    public ResponseEntity<LlmClient.Configuration> config() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.configuration());
    }
    @PostMapping
    public ResponseEntity<LlmClient.Reply> send(@RequestBody ChatRequest request,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.send(Long.parseLong(jwt.getSubject()),request.messages()));
    }
    @PostMapping("/stream")
    public void stream(@RequestBody ChatRequest request,@AuthenticationPrincipal Jwt jwt,HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Accel-Buffering","no");
        try {
            var reply=chat.stream(Long.parseLong(jwt.getSubject()),request.messages(),text->{
                try { event(response,"delta",Map.of("content",text)); }
                catch(IOException e) { throw new UncheckedIOException(e); }
            });
            event(response,"done",Map.of("model",reply.model(),"truncated",reply.truncated()));
        } catch(ChatException error) {
            if(!response.isCommitted()) {
                response.reset();
                throw error; // 尚未发送正文时仍可返回普通 JSON 错误状态码。
            }
            try { event(response,"error",Map.of("message",error.getMessage())); }
            catch(IOException ignored) { /* 浏览器已断开；上游已在 finally 中关闭。 */ }
        }
    }
    private void event(HttpServletResponse response,String name,Object data) throws IOException {
        response.getOutputStream().write(("event: "+name+"\ndata: "+json.writeValueAsString(data)+"\n\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        response.flushBuffer();
    }
}
