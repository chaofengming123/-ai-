package com.example.aiknowledge.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.example.aiknowledge.model.ChatMessage;
import com.example.aiknowledge.exception.ChatException;

/** Unified read-only question answering. Permissions are supplied by Spring Security, never by the request. */
@Service
public class AssistantService {
    private final ChatService chat;
    private final KnowledgeBaseService bases;
    private final KnowledgeBaseRagService rag;
    private final LlmClient llm;
    private final double minimumScore;
    private final Set<Long> active=ConcurrentHashMap.newKeySet();
    private final Semaphore capacity=new Semaphore(2);
    public AssistantService(ChatService chat,KnowledgeBaseService bases,KnowledgeBaseRagService rag,LlmClient llm,
            @Value("${app.assistant.minimum-score:0.55}") double minimumScore) {
        this.chat=chat; this.bases=bases; this.rag=rag; this.llm=llm; this.minimumScore=minimumScore;
    }
    public record Source(int sourceId,long knowledgeBaseId,String knowledgeBaseName,long documentId,String fileName,String text) {}
    public record Result(String model,boolean truncated,String mode,String notice,List<Source> sources) {}
    private record Candidate(long baseId,String baseName,KnowledgeBaseRagService.Hit hit) {}

    public Result answer(long userId,List<ChatMessage> messages,String requestedMode,Long baseId,boolean canRead,
            Consumer<String> delta) {
        ChatService.validate(messages);
        String mode=requestedMode==null?"auto":requestedMode;
        if(!Set.of("auto","knowledge","general").contains(mode)) throw new ChatException(400,"无效的问答模式。");
        if(baseId!=null && baseId<=0) throw new ChatException(400,"无效的知识库编号。");
        if(mode.equals("knowledge") && !canRead) throw new ChatException(403,"当前账号没有知识库和文档读取权限。");
        String question=messages.get(messages.size()-1).content().strip();
        if(!mode.equals("general") && canRead && question.length()>1000)
            throw new ChatException(400,"自动问答或知识库提问最多 1000 个字符。");
        if(!active.add(userId)) throw new ChatException(429,"上一条问答仍在处理中。");
        boolean acquired=false;
        try {
            acquired=capacity.tryAcquire();
            if(!acquired) throw new ChatException(503,"当前问答较多，请稍后重试。");
            if(mode.equals("general") || !canRead)
                return general(userId,messages,delta,canRead?"本轮未检索知识库。":"当前账号无资料读取权限，本轮使用普通对话。");
            var scope=baseId==null?bases.list():List.of(bases.get(baseId));
            if(scope.size()>5) throw new ChatException(400,"知识库较多，请选择一个知识库后提问，或选择普通对话。");
            var candidates=new ArrayList<Candidate>();
            int skipped=0;
            for(var base:scope) {
                var result=(KnowledgeBaseRagService.Retrieval)rag.execute(userId,base.id(),question,false);
                skipped+=result.skipped().size();
                for(var hit:result.matches()) if(hit.score()>=minimumScore)
                    candidates.add(new Candidate(base.id(),base.name(),hit));
            }
            candidates.sort(Comparator.comparingDouble((Candidate c)->c.hit().score()).reversed());
            var seen=new HashSet<String>();
            var selected=candidates.stream().filter(c->seen.add(c.hit().text())).limit(3).toList();
            String coverage=skipped>0?" 有 "+skipped+" 份文档未建立可用索引，未参与检索。":"";
            var generated=GroundedAnswer.generate(llm,question,selected.stream().map(c->c.hit().text()).toList());
            for(var item:selected) rag.verifySources(item.baseId(),List.of(item.hit()));
            if(generated.insufficient() && mode.equals("auto"))
                return general(userId,messages,delta,"未找到足够相关的已索引资料，以下为普通 AI 回答，不作为知识库事实。"+coverage);
            var sources=generated.sourceIds().stream().map(id->{
                var item=selected.get(id-1);
                return new Source(id,item.baseId(),item.baseName(),item.hit().documentId(),item.hit().fileName(),item.hit().text());
            }).toList();
            delta.accept(generated.answer());
            return new Result(llm.configuration().model(),false,"knowledge",
                (generated.insufficient()?"资料不足，请补充文档或调整问题。":"已根据知识库资料回答，请核对来源。")+coverage,sources);
        } finally { if(acquired) capacity.release(); active.remove(userId); }
    }
    private Result general(long userId,List<ChatMessage> messages,Consumer<String> delta,String notice) {
        var reply=chat.stream(userId,messages,delta);
        return new Result(reply.model(),reply.truncated(),"general",notice,List.of());
    }
}
