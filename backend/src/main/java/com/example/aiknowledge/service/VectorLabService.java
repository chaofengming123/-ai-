package com.example.aiknowledge.service;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import com.example.aiknowledge.exception.ChatException;

@Service
public class VectorLabService {
    private final EmbeddingClient embedding;
    private final QdrantClient qdrant;
    private final Semaphore capacity=new Semaphore(1);
    public VectorLabService(EmbeddingClient embedding,QdrantClient qdrant) { this.embedding=embedding; this.qdrant=qdrant; }
    public record Status(boolean exists,long count,int dimensions,String model) {}
    public record Hit(String text,double score) {}
    public record Results(String query,List<Hit> matches) {}
    String collection(long userId) { return "lesson26_u"+userId+"_"+embedding.spaceId(); }
    public Status status(long userId) {
        var info=qdrant.info(collection(userId));
        return new Status(info!=null,info==null?0:info.path("points_count").asLong(),
            info==null?0:info.path("config").path("params").path("vectors").path("size").asInt(),embedding.configuration().model());
    }
    private void validate(String text) {
        if(text==null || text.isBlank() || text.length()>1000) throw new ChatException(400,"每段文字需为 1–1000 个字符。");
    }
    public Status save(long userId,List<String> texts) {
        if(texts==null || texts.isEmpty() || texts.size()>5) throw new ChatException(400,"请提供 1–5 段文字。");
        texts.forEach(this::validate);
        if(!capacity.tryAcquire()) throw new ChatException(503,"向量存储实验正在处理其他请求，请稍后再试。");
        try {
            var unique=texts.stream().map(String::strip).distinct().toList();
            var vectors=embedding.embed(unique);
            var name=collection(userId);
            qdrant.ensure(name,vectors.get(0).length);
            var points=new ArrayList<Map<String,Object>>();
            for(int i=0;i<unique.size();i++) {
                var text=unique.get(i);
                var id=UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString();
                points.add(Map.of("id",id,"vector",vectors.get(i),"payload",Map.of("text",text,"model",embedding.configuration().model())));
            }
            qdrant.upsert(name,points);
            return status(userId);
        } finally { capacity.release(); }
    }
    public Results search(long userId,String query) {
        validate(query);
        if(!capacity.tryAcquire()) throw new ChatException(503,"向量存储实验正在处理其他请求，请稍后再试。");
        try {
            var name=collection(userId); var info=qdrant.info(name);
            if(info==null || info.path("points_count").asLong()==0) throw new ChatException(409,"当前账号和模型还没有保存文字，请先保存候选文字。");
            var vector=embedding.embed(List.of(query)).get(0);
            qdrant.verify(info,vector.length);
            var hits=new ArrayList<Hit>();
            for(var point:qdrant.search(name,vector)) hits.add(new Hit(point.path("payload").path("text").asText(),point.path("score").asDouble()));
            return new Results(query,List.copyOf(hits));
        } finally { capacity.release(); }
    }
}
