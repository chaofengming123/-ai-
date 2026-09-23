package com.example.aiknowledge.service;

import java.util.function.Supplier;

public interface VectorCache {
    QuestionVectorCache.Result get(long userId,String space,String question,boolean bypass,Supplier<double[]> load);
}
