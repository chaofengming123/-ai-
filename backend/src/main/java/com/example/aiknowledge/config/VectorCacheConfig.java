package com.example.aiknowledge.config;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.example.aiknowledge.service.*;

@Configuration
public class VectorCacheConfig {
    @Bean VectorCache vectorCache(StringRedisTemplate redis,@Value("${app.vector-cache.backend:redis}") String backend,
        @Value("${app.vector-cache.namespace:ai-knowledge}") String namespace) {
        return switch(backend) {
            case "memory" -> new QuestionVectorCache();
            case "redis" -> new RedisQuestionVectorCache(redis,namespace);
            default -> throw new IllegalArgumentException("Cache backend must be memory or redis");
        };
    }
}
