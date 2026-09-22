package com.example.aiknowledge.service;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import com.example.aiknowledge.model.ChatMessage;
import com.example.aiknowledge.exception.ChatException;

@Service
public class ChatService {
    private final LlmClient model;
    private final Set<Long> activeUsers=ConcurrentHashMap.newKeySet();
    private final Semaphore capacity=new Semaphore(4);
    public ChatService(LlmClient model) { this.model=model; }
    public LlmClient.Configuration configuration() { return model.configuration(); }
    public LlmClient.Reply send(long userId,List<ChatMessage> messages) {
        return execute(userId,messages,model::complete);
    }
    public LlmClient.Reply stream(long userId,List<ChatMessage> messages,java.util.function.Consumer<String> delta) {
        return execute(userId,messages,prompt->model.stream(prompt,delta));
    }
    private LlmClient.Reply execute(long userId,List<ChatMessage> messages,
            java.util.function.Function<List<ChatMessage>,LlmClient.Reply> action) {
        if(messages==null || messages.isEmpty() || messages.size()>11 || messages.size()%2==0)
            throw new ChatException(400,"对话需包含最后一个问题，最多携带最近五轮完整对话。");
        int characters=0;
        for(int i=0;i<messages.size();i++) {
            var message=messages.get(i);
            String role=i%2==0?"user":"assistant";
            if(message==null || !role.equals(message.role()) || message.content()==null || message.content().isBlank()
                    || message.content().length()>(role.equals("user")?2000:6000))
                throw new ChatException(400,"消息角色或内容不正确；问题最多 2000 个字符。");
            characters+=message.content().length();
        }
        if(characters>12000) throw new ChatException(400,"本次对话内容过长，请开启新对话后重试。");
        if(!activeUsers.add(userId)) throw new ChatException(429,"上一条问题仍在处理中，请等待完成。");
        boolean acquired=false;
        try {
            acquired=capacity.tryAcquire();
            if(!acquired) throw new ChatException(503,"当前对话请求较多，请稍后再试。");
            var prompt=new ArrayList<ChatMessage>();
            prompt.add(new ChatMessage("system","你是中文学习助手。清楚、准确地回答问题，不确定时说明不确定。当前没有知识库检索能力，不要声称已读取用户上传的文档。"));
            prompt.addAll(messages);
            return action.apply(prompt);
        } finally {
            if(acquired) capacity.release();
            activeUsers.remove(userId);
        }
    }
}
