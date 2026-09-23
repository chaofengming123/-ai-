package com.example.aiknowledge.service;

import java.util.*;
import com.example.aiknowledge.exception.ChatException;
import com.example.aiknowledge.service.KnowledgeBaseRagService.Hit;

/** 小规模教学：字面关键词召回 + 向量候选，按排名融合，不混加原始分数。 */
public final class HybridRanking {
    private HybridRanking() {}
    public static final Comparator<Hit> ORDER=Comparator.comparingDouble(Hit::score).reversed()
        .thenComparingLong(Hit::documentId).thenComparingInt(Hit::chunkIndex);
    private record Key(long documentId,int chunkIndex) {}
    public static List<String> keywords(String mode,List<String> input) {
        if(!Set.of("vector","hybrid").contains(mode)) throw new ChatException(400,"检索方式仅支持 vector 或 hybrid。");
        if("vector".equals(mode)) return List.of();
        if(input==null || input.isEmpty() || input.size()>5) throw new ChatException(400,"混合检索需要 1–5 个关键词。");
        var terms=new LinkedHashSet<String>();
        for(String word:input) {
            if(word==null || word.isBlank() || word.length()>40) throw new ChatException(400,"每个关键词需为 1–40 个字符。");
            terms.add(word.strip().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(terms);
    }
    public static Hit score(Hit hit,double score) {
        return new Hit(hit.sourceId(),hit.documentId(),hit.fileName(),hit.chunkIndex(),hit.startOffset(),hit.endOffset(),
            hit.text(),score,hit.indexedAt(),hit.usingPreviousVersion(),hit.note());
    }
    public static List<Hit> fuse(List<Hit> vector,List<Hit> all,List<String> terms) {
        var lexical=new ArrayList<Hit>();
        for(var hit:all) {
            var text=hit.text().toLowerCase(Locale.ROOT);
            long count=terms.stream().filter(text::contains).count();
            if(count>0) lexical.add(score(hit,count));
        }
        var dense=vector.stream().sorted(ORDER).limit(15).toList();
        var sparse=lexical.stream().sorted(ORDER).limit(15).toList();
        var scores=new HashMap<Key,Double>(); var originals=new HashMap<Key,Hit>();
        for(var ranking:List.of(dense,sparse)) {
            for(int i=0;i<ranking.size();i++) {
                var hit=ranking.get(i); var key=new Key(hit.documentId(),hit.chunkIndex());
                originals.putIfAbsent(key,hit);
                // 本实现排名从 1 开始，常数为 60；缺席的一路贡献 0。
                scores.merge(key,1.0/(60+i+1),Double::sum);
            }
        }
        return scores.entrySet().stream().map(e->score(originals.get(e.getKey()),e.getValue())).sorted(ORDER).toList();
    }
}
