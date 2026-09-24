package com.example.aiknowledge.service;

import java.util.*;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import com.example.aiknowledge.mapper.*;
import com.example.aiknowledge.exception.*;

@Service
public class DocumentIndexService {
    // 10000 字符在 800/100 分块与段落边界回退下最多约 27 块，留出安全余量。
    public static final int MAX_CHUNKS=32;
    private final DocumentService files;
    private final DocumentMapper documents;
    private final DocumentIndexMapper indexes;
    private final EmbeddingClient embedding;
    private final QdrantClient qdrant;
    private final Semaphore capacity=new Semaphore(1);
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),r->{ var t=new Thread(r,"document-index-worker"); t.setDaemon(true); return t; });
    @jakarta.annotation.PreDestroy void stop() { worker.shutdownNow(); }
    public DocumentIndexService(DocumentService files,DocumentMapper documents,DocumentIndexMapper indexes,
        EmbeddingClient embedding,QdrantClient qdrant) {
        this.files=files; this.documents=documents; this.indexes=indexes; this.embedding=embedding; this.qdrant=qdrant;
    }
    public record Status(String state,boolean hasActiveIndex,boolean currentModel,int chunks,int dimensions,
        int characters,String model,String note,String error,LocalDateTime indexedAt,boolean recoveryAllowed) {}
    public Status status(long id) {
        requireDocument(id);
        var row=indexes.find(id);
        if(row==null) return new Status("NOT_INDEXED",false,false,0,0,0,null,null,null,null,false);
        return new Status(row.state(),row.activeCollection()!=null,embedding.spaceId().equals(row.activeSpace()),
            row.chunkCount(),row.dimensions(),row.sourceCharacters(),row.activeModel(),row.note(),row.errorMessage(),row.indexedAt(),"PROCESSING".equals(row.state()) && indexes.expired(id)==1);
    }
    private com.example.aiknowledge.model.DocumentInfo requireDocument(long id) {
        var document=documents.findById(id);
        if(document==null) throw new KnowledgeBaseException(KnowledgeBaseException.Kind.NOT_FOUND,"文档不存在。");
        return document;
    }
    // 先持久化 PROCESSING，再交给后台；不把尚未登记的任务回复为已接受。
    public Status submit(long id) { return start(id,true); }
    public Status build(long id) { return start(id,false); }
    private Status start(long id,boolean async) {
        var document=requireDocument(id);
        if(!capacity.tryAcquire()) throw new ChatException(503,"当前有文档正在建立索引，请稍后再试。");
        String attempt=UUID.randomUUID().toString();
        String collection="document27_"+id+"_"+attempt.replace("-", "");
        boolean claimed=false;
        try {
            indexes.initialize(id);
            if(indexes.claim(id,attempt)!=1) throw new ChatException(409,"该文档正在处理；若上次后端意外退出，请十分钟后再试。");
            claimed=true;
            if(async) {
                var accepted=status(id);
                worker.execute(()->{
                    try { execute(id,document,attempt,collection); }
                    catch(RuntimeException ignored) { /* execute 已尝试持久化失败，状态不确定时由原租约规则恢复。 */ }
                });
                return accepted;
            }
        } catch(RuntimeException error) {
            if(claimed) try { indexes.fail(id,attempt,"任务未能提交，请稍后重试。"); } catch(RuntimeException ignored) {}
            capacity.release();
            if(error instanceof RejectedExecutionException) throw new ChatException(503,"后台任务暂时不可用，请稍后重试。");
            throw error;
        }
        return execute(id,document,attempt,collection);
    }
    private Status execute(long id,com.example.aiknowledge.model.DocumentInfo document,String attempt,String collection) {
        boolean published=false, created=false;
        long deadline=System.nanoTime()+java.time.Duration.ofMinutes(5).toNanos();
        try {
            var file=files.download(id);
            var text=DocumentTextExtractor.forIndex(file.name(),file.bytes());
            if(text.content().isBlank()) throw new ChatException(400,"未提取到可索引文字，请提供有文本内容的文件；扫描图片需要先做 OCR。");
            var chunks=TextChunker.split(text.content(),800,100);
            if(chunks.size()>MAX_CHUNKS) throw new ChatException(400,"最多索引 "+MAX_CHUNKS+" 个分块，请拆分文件。");
            var vectors=new ArrayList<double[]>();
            for(int from=0;from<chunks.size();from+=5) {
                if(System.nanoTime()>deadline) throw new ChatException(504,"索引处理时间过长，请缩短文档后重试。");
                var batch=chunks.subList(from,Math.min(from+5,chunks.size())).stream().map(TextChunker.Chunk::text).toList();
                vectors.addAll(IndexEmbeddingRetry.run(()->embedding.embed(batch),deadline));
            }
            int dimensions=vectors.get(0).length;
            if(vectors.stream().anyMatch(v->v.length!=dimensions)) throw new ChatException(502,"不同批次的向量维度不一致，未发布索引。");
            if(System.nanoTime()>deadline) throw new ChatException(504,"索引处理时间过长，未发布索引。");
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.bytes()));
            var points=new ArrayList<Map<String,Object>>();
            for(int i=0;i<chunks.size();i++) {
                var chunk=chunks.get(i);
                points.add(Map.of("id",i,"vector",vectors.get(i),"payload",Map.of(
                    "documentId",id,"knowledgeBaseId",document.knowledgeBaseId(),"chunkIndex",i,
                    "text",chunk.text(),"startOffset",chunk.startOffset(),"endOffset",chunk.endOffset(),
                    "revision",attempt,"model",embedding.configuration().model(),"space",embedding.spaceId(),"sourceSha256",sha)));
            }
            created=true; // 创建响应超时时，集合也可能已创建，失败时尝试清理本次专属集合。
            qdrant.ensure(collection,dimensions);
            qdrant.upsert(collection,points);
            if(indexes.publish(id,attempt,collection,embedding.configuration().model(),embedding.spaceId(),sha,
                chunks.size(),dimensions,text.content().length(),text.note())!=1)
                throw new ChatException(409,"本次索引尝试已被替代，未发布旧结果。");
            published=true;
            return status(id);
        } catch(Exception error) {
            if(!published) {
                // 数据库返回结果不确定时，保守保留集合，避免删掉已经发布的向量。
                boolean safeToRemove=false;
                try {
                    var row=indexes.find(id);
                    if(row!=null && !collection.equals(row.activeCollection())) {
                        String message=error instanceof ChatException || error instanceof DocumentException ? error.getMessage():"索引处理失败，请检查存储与数据库服务后重试。";
                        indexes.fail(id,attempt,message.substring(0,Math.min(message.length(),300)));
                        safeToRemove=true;
                    }
                } catch(Exception ignored) { /* 状态不确定时不删除；租约过期后可重试。 */ }
                if(created && safeToRemove) try { qdrant.remove(collection); } catch(Exception ignored) { /* 留待后续维护，不影响旧索引。 */ }
            }
            if(error instanceof RuntimeException runtime) throw runtime;
            throw new ChatException(503,"无法完成文档索引，请稍后检查状态。");
        } finally { capacity.release(); }
    }
}
