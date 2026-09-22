package com.example.aiknowledge.service;

import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import com.example.aiknowledge.exception.ChatException;

@Service
public class EmbeddingService {
    private final EmbeddingClient client;
    private final Semaphore capacity=new Semaphore(1);
    public EmbeddingService(EmbeddingClient client) { this.client=client; }
    public record Match(int index,String text,double similarity,List<Double> vectorPreview) {}
    public record Comparison(String model,int dimensions,List<Double> queryVectorPreview,List<Match> matches) {}
    public Comparison compare(String query,List<String> candidates) {
        if(candidates==null || candidates.isEmpty() || candidates.size()>5)
            throw new ChatException(400,"请提供 1–5 段候选文字。");
        var inputs=new ArrayList<String>(); inputs.add(query); inputs.addAll(candidates);
        for(var text:inputs) if(text==null || text.isBlank() || text.length()>1000)
            throw new ChatException(400,"问题和每段候选文字需为 1–1000 个字符。");
        if(!capacity.tryAcquire()) throw new ChatException(503,"当前向量实验正在计算，请稍后再试。");
        try {
            var vectors=client.embed(inputs);
            var matches=new ArrayList<Match>();
            for(int i=1;i<vectors.size();i++) matches.add(new Match(i-1,candidates.get(i-1),
                cosine(vectors.get(0),vectors.get(i)),head(vectors.get(i))));
            matches.sort(Comparator.comparingDouble(Match::similarity).reversed().thenComparingInt(Match::index));
            return new Comparison(client.configuration().model(),vectors.get(0).length,head(vectors.get(0)),matches);
        } finally { capacity.release(); }
    }
    private static List<Double> head(double[] vector) {
        return Arrays.stream(vector).limit(8).boxed().toList();
    }
    public static double cosine(double[] a,double[] b) {
        if(a.length==0 || a.length!=b.length) throw new ChatException(502,"向量维度不一致。");
        double normA=0,normB=0;
        for(int i=0;i<a.length;i++) {
            if(!Double.isFinite(a[i]) || !Double.isFinite(b[i])) throw new ChatException(502,"向量包含无效数值。");
            normA=Math.hypot(normA,a[i]); normB=Math.hypot(normB,b[i]);
        }
        if(normA==0 || normB==0 || !Double.isFinite(normA) || !Double.isFinite(normB))
            throw new ChatException(502,"向量长度无效，无法计算相似度。");
        double score=0;
        for(int i=0;i<a.length;i++) score+=(a[i]/normA)*(b[i]/normB);
        return Math.max(-1,Math.min(1,score));
    }
}
