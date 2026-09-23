package com.example.aiknowledge.service;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RagTimingsTests {
    @Test void aggregatesRepeatedStagesAndSeparatesOtherTimeWithoutSleeping() {
        var clock=new AtomicLong(); var timing=new RagTimings(clock::get);
        clock.addAndGet(2_000_000);
        assertEquals("ok",timing.measure(RagTimings.Stage.VECTOR_SEARCH,()->{clock.addAndGet(3_000_000); return "ok";}));
        timing.measure(RagTimings.Stage.VECTOR_SEARCH,()->{clock.addAndGet(4_000_000); return null;});
        var report=timing.snapshot(); assertEquals(9,report.totalMillis()); assertEquals(2,report.otherMillis());
        var step=report.steps().get(1); assertEquals(2,step.calls()); assertEquals(7,step.millis());
        assertEquals(0,report.steps().get(0).calls());
    }
    @Test void recordsFailureWithoutSwallowingExceptionAndSnapshotsAreIndependent() {
        var clock=new AtomicLong(); var timing=new RagTimings(clock::get); var before=timing.snapshot();
        var error=new IllegalStateException("failed");
        assertSame(error,assertThrows(IllegalStateException.class,()->timing.measure(RagTimings.Stage.RERANK,()->{clock.addAndGet(500_000); throw error;})));
        assertEquals(0,before.steps().get(3).calls()); assertEquals(1,timing.snapshot().steps().get(3).calls());
        assertEquals(0,timing.snapshot().steps().get(3).millis());
    }
}
