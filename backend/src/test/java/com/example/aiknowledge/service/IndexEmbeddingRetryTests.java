package com.example.aiknowledge.service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.example.aiknowledge.exception.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexEmbeddingRetryTests {
    long deadline() { return System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10); }
    @Test void retriesTransientFailuresWithBoundedBackoff() {
        var calls=new AtomicInteger(); var delays=new ArrayList<Long>();
        String result=IndexEmbeddingRetry.run(()->{
            if(calls.incrementAndGet()<3) throw new RetryableEmbeddingException(); return "ok";
        },deadline(),delays::add);
        assertEquals("ok",result); assertEquals(3,calls.get()); assertEquals(List.of(500L,1000L),delays);
    }
    @Test void exhaustsAfterThreeCallsButNeverRetriesOrdinaryErrors() {
        var calls=new AtomicInteger();
        assertThrows(RetryableEmbeddingException.class,()->IndexEmbeddingRetry.run(()->{
            calls.incrementAndGet(); throw new RetryableEmbeddingException();
        },deadline(),ms->{})); assertEquals(3,calls.get());
        for(int code:List.of(400,401,429,502,503,504)) {
            calls.set(0);
            assertThrows(ChatException.class,()->IndexEmbeddingRetry.run(()->{
                calls.incrementAndGet(); throw new ChatException(code,"not classified as retryable");
            },deadline(),ms->fail("must not wait"))); assertEquals(1,calls.get());
        }
    }
    @Test void deadlineAndInterruptPreventFurtherCalls() {
        assertThrows(ChatException.class,()->IndexEmbeddingRetry.run(()->{fail("deadline passed");return null;},System.nanoTime()-1,ms->{}));
        var calls=new AtomicInteger();
        try {
            assertThrows(ChatException.class,()->IndexEmbeddingRetry.run(()->{
                calls.incrementAndGet(); throw new RetryableEmbeddingException();
            },deadline(),ms->{throw new InterruptedException();}));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(1,calls.get());
        } finally { Thread.interrupted(); }
    }
    @Test void httpClassificationDoesNotConfuseAuthenticationWithTemporaryFailure() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var status=new AtomicInteger(503);
        server.createContext("/",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(status.get(),-1);exchange.close();});
        server.start();
        try {
            var client=new EmbeddingClient("http://127.0.0.1:"+server.getAddress().getPort()+"/","test",2,"test");
            for(int code:List.of(502,503,504)) {status.set(code);assertThrows(RetryableEmbeddingException.class,()->client.embed(List.of("q")));}
            for(int code:List.of(400,401,403,404,429,500)) {
                status.set(code); var error=assertThrows(ChatException.class,()->client.embed(List.of("q")));
                assertFalse(error instanceof RetryableEmbeddingException);
            }
        } finally {server.stop(0);}
    }
}
