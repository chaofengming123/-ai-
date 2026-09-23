package com.example.aiknowledge.service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisQuestionVectorCacheTests {
    @Test void twoIndependentConnectionsShareVectorsWithTtlAndNamespaceIsolation() {
        var first=new LettuceConnectionFactory("127.0.0.1",6380); var second=new LettuceConnectionFactory("127.0.0.1",6380);
        first.afterPropertiesSet(); first.start(); second.afterPropertiesSet(); second.start();
        var a=new StringRedisTemplate(first); var b=new StringRedisTemplate(second);
        var prefix="test36-"+UUID.randomUUID(); var cacheA=new RedisQuestionVectorCache(a,prefix); var cacheB=new RedisQuestionVectorCache(b,prefix);
        var keys=new ArrayList<String>();
        try {
            String key=cacheA.key(1,"model","q"); keys.add(key);
            assertEquals("MISS",cacheA.get(1,"model","q",false,()->new double[]{1,0}).status());
            var hit=cacheB.get(1,"model"," q ",false,()->{throw new AssertionError("Must share Redis");});
            assertEquals("HIT",hit.status()); assertArrayEquals(new double[]{1,0},hit.vector());
            long ttl=a.getExpire(key,TimeUnit.MILLISECONDS); assertTrue(ttl>0 && ttl<=300_000);
            assertEquals("BYPASS",cacheB.get(1,"model","q",true,()->new double[]{0,1}).status());
            assertArrayEquals(new double[]{1,0},cacheA.get(1,"model","q",false,()->new double[]{0,1}).vector());
            for(long user:List.of(2L,3L)) {
                keys.add(cacheA.key(user,"model","q"));
                assertEquals("MISS",cacheB.get(user,"model","q",false,()->new double[]{1,0}).status());
            }
            keys.add(cacheA.key(1,"other","q"));
            assertEquals("MISS",cacheB.get(1,"other","q",false,()->new double[]{1,0}).status());
            a.expire(key,java.time.Duration.ZERO);
            assertEquals("MISS",cacheB.get(1,"model","q",false,()->new double[]{1,0}).status());
        } finally { a.delete(keys); first.destroy(); second.destroy(); }
    }
    @Test void readFailureDegradesButModelFailureIsNeverSwallowedOrRetried() {
        var redis=mock(StringRedisTemplate.class); when(redis.opsForValue()).thenThrow(new IllegalStateException("offline"));
        var cache=new RedisQuestionVectorCache(redis,"test"); var calls=new AtomicInteger();
        assertEquals("DEGRADED",cache.get(1,"m","q",false,()->{calls.incrementAndGet();return new double[]{1};}).status());
        var failure=new IllegalStateException("model failure");
        assertSame(failure,assertThrows(IllegalStateException.class,()->cache.get(1,"m","q",false,()->{calls.incrementAndGet();throw failure;})));
        assertEquals(2,calls.get());
    }
    @SuppressWarnings("unchecked")
    @Test void writeFailureKeepsSuccessfulVectorAndMalformedCacheIsNotUsed() {
        var redis=mock(StringRedisTemplate.class); var values=(ValueOperations<String,String>)mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new IllegalStateException("write failed")).when(values).set(anyString(),anyString(),any(java.time.Duration.class));
        var cache=new RedisQuestionVectorCache(redis,"test");
        assertEquals("DEGRADED",cache.get(1,"m","q",false,()->new double[]{1}).status());
        for(String bad:List.of("bad","[]","[0,0]","[1e400]","[\"1\"]")) {
            when(values.get(anyString())).thenReturn(bad);
            var result=cache.get(1,"m","q",false,()->new double[]{2});
            assertEquals("DEGRADED",result.status()); assertArrayEquals(new double[]{2},result.vector());
        }
    }
    @Test void bypassAvoidsRedisEntirelyAndKeyDoesNotContainQuestion() {
        var redis=mock(StringRedisTemplate.class); var cache=new RedisQuestionVectorCache(redis,"test");
        assertEquals("BYPASS",cache.get(1,"m","question",true,()->new double[]{1}).status());
        verifyNoInteractions(redis); assertFalse(cache.key(1,"m","question").contains("question"));
        assertNotEquals(cache.key(1,"m","q"),new RedisQuestionVectorCache(redis,"other").key(1,"m","q"));
    }
}
