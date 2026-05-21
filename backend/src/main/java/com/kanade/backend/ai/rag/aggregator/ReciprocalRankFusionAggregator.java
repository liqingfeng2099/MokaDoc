package com.kanade.backend.ai.rag.aggregator;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class ReciprocalRankFusionAggregator implements ContentAggregator {

    private final int k;
    private final int maxResults;

    public ReciprocalRankFusionAggregator(int k, int maxResults) {
        this.k = k;
        this.maxResults = maxResults;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        if (queryToContents.isEmpty()) {
            return Collections.emptyList();
        }
        log.info("Collection<List<Content>>: {}",queryToContents);
        long startTime = System.currentTimeMillis();

        Map<String, Content> contentMap = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        for (Map.Entry<Query, Collection<List<Content>>> entry : queryToContents.entrySet()) {
            Collection<List<Content>> contentsList = entry.getValue();

            for (List<Content> contents : contentsList) {
                for (int rank = 0; rank < contents.size(); rank++) {
                    Content content = contents.get(rank);
                    String key = content.textSegment().text();

                    contentMap.putIfAbsent(key, content);

                    double score = 1.0 / (k + rank + 1);
                    rrfScores.merge(key, score, Double::sum);
                }
            }
        }

        List<Content> sortedContents = contentMap.entrySet().stream()
            .sorted(Map.Entry.<String, Content>comparingByValue(
                (c1, c2) -> Double.compare(
                    rrfScores.getOrDefault(c2.textSegment().text(), 0.0),
                    rrfScores.getOrDefault(c1.textSegment().text(), 0.0))))
            .map(Map.Entry::getValue)
            .limit(maxResults)
            .collect(Collectors.toList());

        long duration = System.currentTimeMillis() - startTime;
        log.info("🔄 [RRF融合完成] 总内容={}, 融合后={}, time={}ms",
            contentMap.size(), sortedContents.size(), duration);

        return sortedContents;
    }
}
