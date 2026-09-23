package com.example.aiknowledge.controller;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.example.aiknowledge.model.ChatMessage;
import com.example.aiknowledge.service.AssistantService;
import com.example.aiknowledge.exception.ChatException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/chat/assistant")
public class AssistantController {
    private final AssistantService assistant;
    private final JsonMapper json=JsonMapper.builder().build();
    public AssistantController(AssistantService assistant) { this.assistant=assistant; }
    public record Request(List<ChatMessage> messages,String mode,Long knowledgeBaseId) {}
    @PostMapping
    public void answer(@RequestBody Request request,Authentication authentication,HttpServletResponse response) throws IOException {
        var permissions=authentication.getAuthorities().stream().map(a->a.getAuthority()).toList();
        boolean canRead=permissions.containsAll(List.of("knowledge-base:read","document:read"));
        long userId=Long.parseLong(((Jwt)authentication.getPrincipal()).getSubject());
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Accel-Buffering","no");
        try {
            var result=assistant.answer(userId,request.messages(),request.mode(),request.knowledgeBaseId(),canRead,text->{
                try { event(response,"delta",Map.of("content",text)); }
                catch(IOException error) { throw new UncheckedIOException(error); }
            });
            event(response,"done",result);
        } catch(ChatException error) {
            if(!response.isCommitted()) { response.reset(); throw error; }
            event(response,"error",Map.of("message",error.getMessage()));
        } catch(RuntimeException error) {
            if(!response.isCommitted()) { response.reset(); throw error; }
            if(!(error instanceof UncheckedIOException))
                event(response,"error",Map.of("message","问答处理中断，请稍后重试。"));
        }
    }
    private void event(HttpServletResponse response,String event,Object value) throws IOException {
        response.getOutputStream().write(("event: "+event+"\ndata: "+json.writeValueAsString(value)+"\n\n").getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }
}
