package com.example.aiknowledge.service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import com.example.aiknowledge.mapper.*;
import com.example.aiknowledge.exception.*;

@Service
public class DocumentSearchService {
    private final DocumentMapper documents;
    private final DocumentIndexMapper indexes;
    private final EmbeddingClient embedding;
    private final QdrantClient qdrant;
    private final Semaphore capacity=new Semaphore(2);
    public DocumentSearchService(DocumentMapper documents,DocumentIndexMapper indexes,EmbeddingClient embedding,QdrantClient qdrant) {
        this.documents=documents; this.indexes=indexes; this.embedding=embedding; this.qdrant=qdrant;
    }
    public record Hit(int chunkIndex,int startOffset,int endOffset,String text,double score) {}
    public record Result(long documentId,long knowledgeBaseId,String fileName,String query,String model,
        LocalDateTime indexedAt,boolean usingPreviousVersion,String note,List<Hit> matches) {}
    public Result search(long id,String query) {
        return execute(id,query,null,false);
    }
    // 知识库检索复用同一个问题向量，仍执行每份文档的完整来源与版本校验。
    public Result searchWithVector(long id,String query,double[] vector) {
        return execute(id,query,Objects.requireNonNull(vector),false);
    }
    public Result scanForKeywords(long id,String query) {
        return execute(id,query,null,true);
    }
    private Result execute(long id,String query,double[] suppliedVector,boolean scan) {
        if(query==null || query.isBlank() || query.length()>1000) throw new ChatException(400,"检索问题需为 1–1000 个字符。");
        if(!capacity.tryAcquire()) throw new ChatException(503,"当前文档检索较多，请稍后再试。");
        try {
            var document=documents.findById(id);
            if(document==null) throw new KnowledgeBaseException(KnowledgeBaseException.Kind.NOT_FOUND,"文档不存在。");
            var index=indexes.find(id);
            if(index==null || index.activeCollection()==null) throw new ChatException(409,"文档尚无成功索引，请先由编辑者或管理员建立索引。");
            if(!embedding.spaceId().equals(index.activeSpace())) throw new ChatException(409,"模型配置与已保存索引不一致，请重新建立索引。");
            // 先检查集合可用，避免存储缺失时仍发送问题到模型。
            var info=qdrant.info(index.activeCollection());
            if(info==null) throw new ChatException(409,"已发布的索引集合缺失，请重新建立索引。");
            qdrant.verify(info,index.dimensions());
            tools.jackson.databind.JsonNode points;
            if(scan) {
                points=qdrant.scanDocument(index.activeCollection());
                if(index.chunkCount()<1 || index.chunkCount()>DocumentIndexService.MAX_CHUNKS || !points.isArray() || points.size()!=index.chunkCount())
                    throw new ChatException(502,"文档索引片段不完整，请检查或重建索引。");
            } else {
                var vector=suppliedVector==null?embedding.embed(List.of(query.strip())).get(0):suppliedVector;
                if(vector.length!=index.dimensions()) throw new ChatException(409,"问题向量维度已变化，请重新建立索引。");
                points=qdrant.search(index.activeCollection(),vector);
                if(!points.isArray() || points.size()>3) throw new ChatException(502,"检索响应格式不正确。");
            }
            var hits=new ArrayList<Hit>(); var seen=new HashSet<Integer>();
            for(var point:points) {
                var payload=point.path("payload");
                int chunk=payload.path("chunkIndex").asInt(-1),start=payload.path("startOffset").asInt(-1),end=payload.path("endOffset").asInt(-1);
                String text=payload.path("text").asText(); double score=scan?0:point.path("score").asDouble(Double.NaN);
                if(payload.path("documentId").asLong(-1)!=id || payload.path("knowledgeBaseId").asLong(-1)!=document.knowledgeBaseId()
                    || !index.sourceSha256().equals(payload.path("sourceSha256").asText())
                    || !index.activeSpace().equals(payload.path("space").asText())
                    || !index.activeModel().equals(payload.path("model").asText())
                    || !index.activeCollection().equals("document27_"+id+"_"+payload.path("revision").asText().replace("-",""))
                    || chunk<0 || chunk>=index.chunkCount() || !seen.add(chunk) || start<0 || end<=start
                    || end>index.sourceCharacters() || end-start!=text.length() || text.length()>800
                    || !Double.isFinite(score)) throw new ChatException(502,"检索片段与发布的文档版本不一致，请检查或重建索引。");
                hits.add(new Hit(chunk,start,end,text,score));
            }
            // 模型调用可能较慢；若期间发布了新版本，不把旧结果当作当前结果返回。
            var latest=indexes.find(id);
            if(latest==null || !index.activeCollection().equals(latest.activeCollection()))
                throw new ChatException(409,"检索期间文档索引已更新，请重新检索。");
            return new Result(id,document.knowledgeBaseId(),document.fileName(),query.strip(),index.activeModel(),
                index.indexedAt(),!"READY".equals(latest.state()),index.note(),List.copyOf(hits));
        } finally { capacity.release(); }
    }
}
