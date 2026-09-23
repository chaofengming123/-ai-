package com.example.aiknowledge.service;

import java.util.function.Supplier;
import com.example.aiknowledge.exception.ChatException;
import com.example.aiknowledge.exception.RetryableEmbeddingException;

public final class IndexEmbeddingRetry {
    private IndexEmbeddingRetry() {}
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    public static <T> T run(Supplier<T> operation,long deadline) {
        return run(operation,deadline,Thread::sleep);
    }
    static <T> T run(Supplier<T> operation,long deadline,Sleeper sleeper) {
        for(int attempt=0; ; attempt++) {
            if(Thread.currentThread().isInterrupted()) throw new ChatException(503,"索引任务已中断。");
            if(System.nanoTime()>=deadline) throw new ChatException(504,"索引处理时间过长，已停止重试。");
            try { return operation.get(); }
            catch(RetryableEmbeddingException error) {
                if(attempt>=2) throw error;
                long delay=500L << attempt;
                if(deadline-System.nanoTime()<=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay)) throw error;
                try { sleeper.sleep(delay); }
                catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ChatException(503,"索引任务已中断，已停止重试。");
                }
            }
        }
    }
}
