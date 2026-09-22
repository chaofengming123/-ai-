package com.example.aiknowledge;

import org.junit.jupiter.api.Test;
import com.example.aiknowledge.service.EmbeddingService;
import com.example.aiknowledge.exception.ChatException;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingMathTests {
    @Test void cosineMeasuresDirectionRatherThanMagnitude() {
        assertEquals(1,EmbeddingService.cosine(new double[]{1,2},new double[]{3,6}),1e-12);
        assertEquals(0,EmbeddingService.cosine(new double[]{1,0},new double[]{0,1}),1e-12);
        assertEquals(-1,EmbeddingService.cosine(new double[]{1,0},new double[]{-1,0}),1e-12);
    }
    @Test void rejectsZeroMismatchedAndNonfiniteVectors() {
        for(var vector:new double[][]{{0,0},{1},{Double.NaN,1},{Double.POSITIVE_INFINITY,0}})
            assertThrows(ChatException.class,()->EmbeddingService.cosine(new double[]{1,2},vector));
    }
}
