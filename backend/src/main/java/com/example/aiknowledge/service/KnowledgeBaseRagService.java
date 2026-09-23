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
    private final RerankClient reranker;
    private final VectorCache vectorCache;
    private final Semaphore capacity=new Semaphore(2);
    private final Set<Long> activeUsers=ConcurrentHashMap.newKeySet();
    public KnowledgeBaseRagService(KnowledgeBaseService bases,DocumentMapper documents,DocumentIndexMapper indexes,
        DocumentSearchService search,EmbeddingClient embedding,LlmClient llm,RerankClient reranker,VectorCache vectorCache) {
        this.bases=bases; this.documents=documents; this.indexes=indexes; this.search=search; this.embedding=embedding; this.llm=llm;
        this.reranker=reranker;
        this.vectorCache=vectorCache;
    }
    public record Skipped(long documentId,String fileName,String reason) {}
    public record Hit(int sourceId,long documentId,String fileName,int chunkIndex,int startOffset,int endOffset,
        String text,double score,java.time.LocalDateTime indexedAt,boolean usingPreviousVersion,String note) {}
    public record Retrieval(long knowledgeBaseId,String knowledgeBaseName,String query,String embeddingModel,
        int totalDocuments,int searchedDocuments,List<Skipped> skipped,List<Hit> matches,String mode,List<String> keywords,RerankInfo rerank,RagTimings.Report timings,String embeddingCache) {}
    public record RerankInfo(boolean enabled,boolean applied,String model,int candidateCount,List<Hit> before) {}
    public record Answer(Retrieval retrieval,boolean insufficient,String answer,String model,List<Hit> sources) {}
    public void verifySources(long baseId,List<Hit> hits) {
        for(var hit:hits) {
            var document=documents.findById(hit.documentId());
            var index=indexes.find(hit.documentId());
            if(document==null || document.knowledgeBaseId()!=baseId || index==null || index.activeCollection()==null
                    || !Objects.equals(index.indexedAt(),hit.indexedAt()) || !embedding.spaceId().equals(index.activeSpace()))
                throw new ChatException(409,"回答期间文档索引已变化，请重新提问。");
        }
    }
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
        return execute(userId,baseId,question,answer,"vector",List.of());
    }
    private void collect(List<Hit> target,DocumentSearchService.Result found,long baseId) {
        if(found.knowledgeBaseId()!=baseId) throw new ChatException(409,"文档归属已变化，请重新检索。");
        for(var hit:found.matches()) target.add(new Hit(0,found.documentId(),found.fileName(),hit.chunkIndex(),hit.startOffset(),hit.endOffset(),
            hit.text(),hit.score(),found.indexedAt(),found.usingPreviousVersion(),found.note()));
    }
    public Object execute(long userId,long baseId,String question,boolean answer,String requestedMode,List<String> inputKeywords) {
        return execute(userId,baseId,question,answer,requestedMode,inputKeywords,false);
    }
    private List<Hit> numbered(List<Hit> hits) {
        var result=new ArrayList<Hit>();
        for(var hit:hits) result.add(new Hit(result.size()+1,hit.documentId(),hit.fileName(),hit.chunkIndex(),hit.startOffset(),hit.endOffset(),
            hit.text(),hit.score(),hit.indexedAt(),hit.usingPreviousVersion(),hit.note()));
        return List.copyOf(result);
    }
    public Object execute(long userId,long baseId,String question,boolean answer,String requestedMode,List<String> inputKeywords,boolean rerank) {
        return execute(userId,baseId,question,answer,requestedMode,inputKeywords,rerank,false);
    }
    public Object execute(long userId,long baseId,String question,boolean answer,String requestedMode,List<String> inputKeywords,boolean rerank,boolean bypassCache) {
        var timings=new RagTimings();
        String mode=requestedMode==null?"vector":requestedMode;
        var keywords=HybridRanking.keywords(mode,inputKeywords);
        if(question==null || question.isBlank() || question.length()>1000) throw new ChatException(400,"知识库问题需为 1–1000 个字符。");
        if(!activeUsers.add(userId)) throw new ChatException(429,"上一条知识库请求仍在处理中，请等待完成。");
        boolean acquired=false;
        try {
            acquired=capacity.tryAcquire(); if(!acquired) throw new ChatException(503,"当前知识库请求较多，请稍后再试。");
            var base=bases.get(baseId);
            if(answer && !llm.configuration().configured()) throw new ChatException(503,"请先配置 GLM 的 LLM_API_KEY 并重启后端。");
            if(rerank && !reranker.configuration().configured()) throw new ChatException(503,"请先配置硅基流动的 RERANK_API_KEY 并重启后端。");
            var before=snapshot(baseId); var eligible=new ArrayList<Item>(); var skipped=new ArrayList<Skipped>();
            for(var item:before) {
                var row=item.index();
                String reason=row==null || row.activeCollection()==null?"尚无成功索引":!embedding.spaceId().equals(row.activeSpace())?"模型配置不兼容":null;
                if(reason==null) eligible.add(item);
                else skipped.add(new Skipped(item.document().id(),item.document().fileName(),reason));
            }
            if(eligible.size()>5) throw new ChatException(400,"本课每个知识库最多检索 5 份兼容的成功索引，请使用较小的练习知识库。");
            List<Hit> candidates=new ArrayList<>(); var all=new ArrayList<Hit>();
            String cacheStatus="NOT_USED";
            if(!eligible.isEmpty()) {
                var cached=vectorCache.get(userId,embedding.spaceId(),question,bypassCache,
                    ()->timings.measure(RagTimings.Stage.EMBEDDING,()->embedding.embed(List.of(question.strip())).get(0)));
                var vector=cached.vector(); cacheStatus=cached.status();
                for(var item:eligible) {
                    var found=timings.measure(RagTimings.Stage.VECTOR_SEARCH,()->search.searchWithVector(item.document().id(),question.strip(),vector));
                    collect(candidates,found,baseId);
                    if("hybrid".equals(mode)) collect(all,timings.measure(RagTimings.Stage.KEYWORD_SCAN,
                        ()->search.scanForKeywords(item.document().id(),question.strip())),baseId);
                }
            }
            if("hybrid".equals(mode)) candidates=HybridRanking.fuse(candidates,all,keywords);
            else candidates.sort(HybridRanking.ORDER);
            var seen=new HashSet<String>(); var pool=new ArrayList<Hit>();
            for(var hit:candidates) {
                if(!seen.add(hit.text())) continue;
                pool.add(hit);
                if(pool.size()==(rerank?6:3)) break;
            }
            unchanged(baseId,before);
            var baseline=numbered(pool.stream().limit(3).toList());
            var selected=baseline;
            boolean applied=rerank && pool.size()>1;
            if(applied) {
                var indices=timings.measure(RagTimings.Stage.RERANK,()->reranker.select(question.strip(),pool.stream().map(Hit::text).toList()));
                selected=numbered(indices.stream().map(pool::get).toList());
                unchanged(baseId,before);
            }
            var rerankInfo=new RerankInfo(rerank,applied,applied?reranker.configuration().model():null,pool.size(),rerank?baseline:List.of());
            var finalSelected=selected;
            GroundedAnswer.Generated generated=null;
            if(answer) {
                generated=selected.isEmpty()?GroundedAnswer.generate(llm,question.strip(),List.of()):
                    timings.measure(RagTimings.Stage.GENERATION,()->GroundedAnswer.generate(llm,question.strip(),finalSelected.stream().map(Hit::text).toList()));
                unchanged(baseId,before);
            }
            var retrieval=new Retrieval(baseId,base.name(),question.strip(),embedding.configuration().model(),before.size(),eligible.size(),List.copyOf(skipped),selected,mode,keywords,rerankInfo,timings.snapshot(),cacheStatus);
            if(!answer) return retrieval;
            return new Answer(retrieval,generated.insufficient(),generated.answer(),llm.configuration().model(),
                generated.sourceIds().stream().map(number->finalSelected.get(number-1)).toList());
        } finally { if(acquired) capacity.release(); activeUsers.remove(userId); }
    }
}
