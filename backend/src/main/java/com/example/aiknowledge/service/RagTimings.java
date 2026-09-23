package com.example.aiknowledge.service;

import java.util.*;
import java.util.function.*;
import java.util.concurrent.TimeUnit;

/** 每个请求创建一个实例，记录顺序执行的阶段，不保存正文或密钥。 */
public final class RagTimings {
    enum Stage { EMBEDDING, VECTOR_SEARCH, KEYWORD_SCAN, RERANK, GENERATION }
    public record Step(String stage,int calls,long millis) {}
    public record Report(long totalMillis,long otherMillis,List<Step> steps) {}
    private final LongSupplier clock;
    private final long started;
    private final long[] nanos=new long[Stage.values().length];
    private final int[] calls=new int[Stage.values().length];
    RagTimings() { this(System::nanoTime); }
    RagTimings(LongSupplier clock) { this.clock=clock; this.started=clock.getAsLong(); }
    <T> T measure(Stage stage,Supplier<T> work) {
        long begin=clock.getAsLong();
        try { return work.get(); }
        finally { nanos[stage.ordinal()]+=clock.getAsLong()-begin; calls[stage.ordinal()]++; }
    }
    Report snapshot() {
        long total=clock.getAsLong()-started,measured=0;
        var steps=new ArrayList<Step>();
        for(var stage:Stage.values()) {
            long elapsed=nanos[stage.ordinal()]; measured+=elapsed;
            steps.add(new Step(stage.name(),calls[stage.ordinal()],TimeUnit.NANOSECONDS.toMillis(elapsed)));
        }
        return new Report(TimeUnit.NANOSECONDS.toMillis(total),TimeUnit.NANOSECONDS.toMillis(Math.max(0,total-measured)),List.copyOf(steps));
    }
}
