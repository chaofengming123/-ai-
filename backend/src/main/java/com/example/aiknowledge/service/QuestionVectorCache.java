package com.example.aiknowledge.service;

import java.util.*;
import java.util.function.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** 单进程、按用户隔离的问题向量缓存；网络计算不占用缓存锁。 */
public final class QuestionVectorCache implements VectorCache {
    public record Result(double[] vector,String status) {}
    private record Key(long userId,String space,String questionHash) {}
    private record Entry(double[] vector,long created) {}
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final int capacity;
    private final long ttl;
    private final LongSupplier clock;
    public QuestionVectorCache() { this(128,Duration.ofMinutes(5).toNanos(),System::nanoTime); }
    QuestionVectorCache(int capacity,long ttl,LongSupplier clock) {
        if(capacity<1 || ttl<1) throw new IllegalArgumentException();
        this.capacity=capacity; this.ttl=ttl; this.clock=clock;
    }
    private String hash(String question) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(question.strip().getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public Result get(long userId,String space,String question,boolean bypass,Supplier<double[]> load) {
        if(bypass) return new Result(load.get().clone(),"BYPASS");
        var key=new Key(userId,space,hash(question));
        synchronized(entries) {
            long now=clock.getAsLong();
            entries.values().removeIf(entry->now-entry.created()>=ttl);
            var cached=entries.get(key);
            if(cached!=null) return new Result(cached.vector().clone(),"HIT");
        }
        // 失败直接抛出，不缓存异常；只保存成功返回的向量副本。
        var vector=load.get().clone();
        synchronized(entries) {
            entries.put(key,new Entry(vector,clock.getAsLong()));
            while(entries.size()>capacity) entries.remove(entries.keySet().iterator().next());
        }
        return new Result(vector.clone(),"MISS");
    }
}
