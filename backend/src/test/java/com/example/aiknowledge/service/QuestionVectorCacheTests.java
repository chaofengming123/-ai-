package com.example.aiknowledge.service;

import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestionVectorCacheTests {
    @Test void isolatesUserAndModelAndNormalizesOnlyOuterWhitespace() {
        var cache=new QuestionVectorCache(); var calls=new AtomicInteger();
        java.util.function.Supplier<double[]> load=()->{calls.incrementAndGet();return new double[]{1,0};};
        assertEquals("MISS",cache.get(1,"m"," q ",false,load).status());
        assertEquals("HIT",cache.get(1,"m","q",false,load).status());
        assertEquals("MISS",cache.get(2,"m","q",false,load).status());
        assertEquals("MISS",cache.get(1,"n","q",false,load).status());
        assertEquals("MISS",cache.get(1,"m","Q",false,load).status()); assertEquals(4,calls.get());
    }
    @Test void expiresAtTtlWithoutExtendingOnHitAndEvictsLeastRecentlyUsed() {
        var clock=new AtomicLong(); var cache=new QuestionVectorCache(2,10,clock::get);
        cache.get(1,"m","a",false,()->new double[]{1}); cache.get(1,"m","b",false,()->new double[]{1});
        clock.set(9); assertEquals("HIT",cache.get(1,"m","a",false,()->new double[]{2}).status());
        cache.get(1,"m","c",false,()->new double[]{1});
        assertEquals("MISS",cache.get(1,"m","b",false,()->new double[]{1}).status());
        var separate=new QuestionVectorCache(2,10,clock::get);
        separate.get(1,"m","a",false,()->new double[]{1}); clock.set(18);
        assertEquals("HIT",separate.get(1,"m","a",false,()->new double[]{1}).status()); clock.set(19);
        assertEquals("MISS",separate.get(1,"m","a",false,()->new double[]{1}).status());
    }
    @Test void bypassDoesNotReadOrReplaceExistingValueAndArraysAreDefensivelyCopied() {
        var cache=new QuestionVectorCache(); double[] original={1};
        var first=cache.get(1,"m","q",false,()->original); original[0]=2; first.vector()[0]=3;
        assertEquals(9,cache.get(1,"m","q",true,()->new double[]{9}).vector()[0]);
        var hit=cache.get(1,"m","q",false,()->new double[]{8}); assertEquals(1,hit.vector()[0]); hit.vector()[0]=4;
        assertEquals(1,cache.get(1,"m","q",false,()->new double[]{8}).vector()[0]);
    }
    @Test void exceptionsAreNotCached() {
        var cache=new QuestionVectorCache();
        assertThrows(IllegalStateException.class,()->cache.get(1,"m","q",false,()->{throw new IllegalStateException();}));
        assertEquals("MISS",cache.get(1,"m","q",false,()->new double[]{1}).status());
    }
}
