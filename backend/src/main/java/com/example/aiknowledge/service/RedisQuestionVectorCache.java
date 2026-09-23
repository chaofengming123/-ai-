package com.example.aiknowledge.service;

import java.util.*;
import java.util.function.Supplier;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 缓存故障只影响复用，不吞掉真正的模型错误。 */
public final class RedisQuestionVectorCache implements VectorCache {
    private final StringRedisTemplate redis;
    private final String prefix;
    private final JsonMapper json=JsonMapper.builder().build();
    public RedisQuestionVectorCache(StringRedisTemplate redis,String namespace) {
        if(!namespace.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid cache namespace");
        this.redis=redis; this.prefix=namespace+":qv:v1:";
    }
    String key(long user,String space,String question) {
        try {
            var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((space+"\n"+question.strip()).getBytes(StandardCharsets.UTF_8)));
            return prefix+user+":"+hash;
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private double[] decode(String text) {
        if(text.length()>200_000) throw new IllegalArgumentException("Oversized cache value");
        var values=json.readTree(text);
        if(!values.isArray() || values.isEmpty() || values.size()>4096) throw new IllegalArgumentException("Invalid vector");
        var vector=new double[values.size()]; double norm=0;
        for(int i=0;i<vector.length;i++) {
            if(!values.get(i).isNumber()) throw new IllegalArgumentException("Invalid vector");
            vector[i]=values.get(i).asDouble(); norm=Math.hypot(norm,vector[i]);
        }
        if(!Double.isFinite(norm) || norm==0) throw new IllegalArgumentException("Invalid vector");
        return vector;
    }
    public QuestionVectorCache.Result get(long user,String space,String question,boolean bypass,Supplier<double[]> load) {
        if(bypass) return new QuestionVectorCache.Result(load.get().clone(),"BYPASS");
        String key=key(user,space,question); boolean degraded=false;
        try {
            String cached=redis.opsForValue().get(key);
            if(cached!=null) return new QuestionVectorCache.Result(decode(cached),"HIT");
        } catch(RuntimeException failure) { degraded=true; }
        // 模型调用在缓存异常处理之外：模型失败必须向上抛出，不能重复调用。
        var vector=load.get().clone();
        if(!degraded) {
            try { redis.opsForValue().set(key,json.writeValueAsString(vector),Duration.ofMinutes(5)); }
            catch(RuntimeException failure) { degraded=true; }
        }
        return new QuestionVectorCache.Result(vector,degraded?"DEGRADED":"MISS");
    }
}
