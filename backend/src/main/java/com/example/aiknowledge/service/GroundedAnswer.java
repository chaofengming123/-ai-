package com.example.aiknowledge.service;

import java.util.*;
import com.example.aiknowledge.model.ChatMessage;
import com.example.aiknowledge.exception.ChatException;
import tools.jackson.databind.json.JsonMapper;

/** 两种 RAG 共用生成与引用编号校验，来源实体由调用者匹配。 */
final class GroundedAnswer {
    private GroundedAnswer() {}
    record Generated(boolean insufficient,String answer,List<Integer> sourceIds) {}
    static Generated generate(LlmClient llm,String question,List<String> texts) {
        if(texts.isEmpty()) return new Generated(true,DocumentAnswerService.INSUFFICIENT,List.of());
        if(texts.size()>3 || texts.stream().anyMatch(t->t.length()>800)) throw new ChatException(400,"回答上下文超过本课限制。");
        var json=JsonMapper.builder().build();
        var passages=new ArrayList<Map<String,Object>>();
        for(int i=0;i<texts.size();i++) passages.add(Map.of("sourceId",i+1,"text",texts.get(i)));
        var reply=llm.complete(List.of(new ChatMessage("system",DocumentAnswerService.SYSTEM),
            new ChatMessage("user",json.writeValueAsString(Map.of("question",question,"passages",passages)))));
        if(reply.truncated()) throw new ChatException(502,"模型回答被截断，未作为完整答案展示，请缩短问题后重试。");
        try {
            var data=json.readTree(reply.content());
            if(!data.isObject() || !data.path("insufficient").isBoolean() || !data.path("answer").isString()
                || !data.path("sourceIds").isArray()) throw new IllegalArgumentException();
            boolean insufficient=data.path("insufficient").asBoolean(); String answer=data.path("answer").asText();
            if(answer.isBlank() || answer.length()>2000 || data.path("sourceIds").size()>texts.size()) throw new IllegalArgumentException();
            var ids=new ArrayList<Integer>();
            for(var value:data.path("sourceIds")) {
                if(!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException();
                int id=value.asInt();
                if(id<1 || id>texts.size() || ids.contains(id)) throw new IllegalArgumentException();
                ids.add(id);
            }
            if(insufficient && !ids.isEmpty() || !insufficient && ids.isEmpty()) throw new IllegalArgumentException();
            return new Generated(insufficient,insufficient?DocumentAnswerService.INSUFFICIENT:answer,List.copyOf(ids));
        } catch(Exception error) { throw new ChatException(502,"模型返回的答案格式或引用编号无效，未展示未经校验的回答；系统没有自动重试。"); }
    }
}
