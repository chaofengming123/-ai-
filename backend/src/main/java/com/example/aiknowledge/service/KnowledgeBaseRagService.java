package com.example.aiknowledge.service;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import com.example.aiknowledge.mapper.*;
import com.example.aiknowledge.model.*;
import com.example.aiknowledge.exception.*;

@Service
public class KnowledgeBaseRagService {
    private final KnowledgeBaseService bases;
    private final DocumentMapper documents;
    private final DocumentIndexMapper indexes;
    private final DocumentSearchService search;
    private final EmbeddingClient embedding;
    private final LlmClient llm;
    private final Semaphore capacity=new Semaphore(2);
    private final Set<Long> activeUsers=ConcurrentHashMap.newKeySet();
    public KnowledgeBaseRagService(KnowledgeBaseService bases,DocumentMapper documents,DocumentIndexMapper indexes,
        DocumentSearchService search,EmbeddingClient embedding,LlmClient llm) {
        this.bases=bases; this.documents=documents; this.indexes=indexes; this.search=search; this.embedding=embedding; this.llm=llm;
    }
    public record Skipped(long documentId,String fileName,String reason) {}
    public record Hit(int sourceId,long documentId,String fileName,int chunkIndex,int startOffset,int endOffset,
        String text,double score,java.time.LocalDateTime indexedAt,boolean usingPreviousVersion,String note) {}
    public record Retrieval(long knowledgeBaseId,String knowledgeBaseName,String query,String embeddingModel,
        int totalDocuments,int searchedDocuments,List<Skipped> skipped,List<Hit> matches) {}
    public record Answer(Retrieval retrieval,boolean insufficient,String answer,String model,List<Hit> sources) {}
    private record Item(DocumentInfo document,DocumentIndex index) {}
    private List<Item> snapshot(long baseId) {
        return documents.list(baseId).stream().map(d->new Item(d,indexes.find(d.id()))).toList();
    }
    private Map<Long,String> versions(List<Item> items) {
        var result=new TreeMap<Long,String>();
        for(var item:items) result.put(item.document().id(),item.index()==null?"":Objects.toString(item.index().activeCollection(),"")+"/"+Objects.toString(item.index().activeSpace(),""));
        return result;
    }
    private void unchanged(long id,List<Item> before) {
        if(!versions(before).equals(versions(snapshot(id)))) throw new ChatException(409,"处理期间知识库文档或索引已变化，请重新提交。");
    }
    public Object execute(long userId,long baseId,String question,boolean answer) {
        if(question==null || question.isBlank() || question.length()>1000) throw new ChatException(400,"知识库问题需为 1–1000 个字符。");
        if(!activeUsers.add(userId)) throw new ChatException(429,"上一条知识库请求仍在处理中，请等待完成。");
        boolean acquired=false;
        try {
            acquired=capacity.tryAcquire(); if(!acquired) throw new ChatException(503,"当前知识库请求较多，请稍后再试。");
            var base=bases.get(baseId);
            if(answer && !llm.configuration().configured()) throw new ChatException(503,"请先配置 GLM 的 LLM_API_KEY 并重启后端。");
            var before=snapshot(baseId); var eligible=new ArrayList<Item>(); var skipped=new ArrayList<Skipped>();
            for(var item:before) {
                var row=item.index();
                String reason=row==null || row.activeCollection()==null?"尚无成功索引":!embedding.spaceId().equals(row.activeSpace())?"模型配置不兼容":null;
                if(reason==null) eligible.add(item);
                else skipped.add(new Skipped(item.document().id(),item.document().fileName(),reason));
            }
            if(eligible.size()>5) throw new ChatException(400,"本课每个知识库最多检索 5 份兼容的成功索引，请使用较小的练习知识库。");
            var candidates=new ArrayList<Hit>();
            if(!eligible.isEmpty()) {
                var vector=embedding.embed(List.of(question.strip())).get(0);
                for(var item:eligible) {
                    var found=search.searchWithVector(item.document().id(),question.strip(),vector);
                    if(found.knowledgeBaseId()!=baseId) throw new ChatException(409,"文档归属已变化，请重新检索。");
                    for(var hit:found.matches()) candidates.add(new Hit(0,found.documentId(),found.fileName(),hit.chunkIndex(),hit.startOffset(),hit.endOffset(),
                        hit.text(),hit.score(),found.indexedAt(),found.usingPreviousVersion(),found.note()));
                }
            }
            candidates.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparingLong(Hit::documentId).thenComparingInt(Hit::chunkIndex));
            var seen=new HashSet<String>(); var selected=new ArrayList<Hit>();
            for(var hit:candidates) {
                if(!seen.add(hit.text())) continue;
                selected.add(new Hit(selected.size()+1,hit.documentId(),hit.fileName(),hit.chunkIndex(),hit.startOffset(),hit.endOffset(),hit.text(),hit.score(),hit.indexedAt(),hit.usingPreviousVersion(),hit.note()));
                if(selected.size()==3) break;
            }
            unchanged(baseId,before);
            var retrieval=new Retrieval(baseId,base.name(),question.strip(),embedding.configuration().model(),before.size(),eligible.size(),List.copyOf(skipped),List.copyOf(selected));
            if(!answer) return retrieval;
            var generated=GroundedAnswer.generate(llm,question.strip(),selected.stream().map(Hit::text).toList());
            unchanged(baseId,before);
            return new Answer(retrieval,generated.insufficient(),generated.answer(),llm.configuration().model(),
                generated.sourceIds().stream().map(number->selected.get(number-1)).toList());
        } finally { if(acquired) capacity.release(); activeUsers.remove(userId); }
    }
}
