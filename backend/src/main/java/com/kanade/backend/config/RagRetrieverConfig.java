package com.kanade.backend.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Configuration
public class RagRetrieverConfig {

    @Value("${elasticsearch.index-name:rag_documents}")
    private String indexName;

    @Value("${rag.es.max-results:10}")
    private int maxResults;

    @Value("${rag.es.min-score:0.75}")
    private double minScore;

    @Bean
    public ContentRetriever vectorRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                            EmbeddingModel embeddingModel) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
    }

    @Bean
    public ContentRetriever textRetriever(ElasticsearchClient esClient) {
        return new ContentRetriever() {
            @Override
            public List<Content> retrieve(Query query) {
                long startTime = System.currentTimeMillis();
                String queryText = query.text();
                log.info("🔍 [ES全文检索-开始] 关键词: '{}', 最大结果数: {}", queryText, maxResults);

                try {
                    SearchResponse<Map> response = esClient.search(s -> s
                            .index(indexName)
                            .query(q -> q
                                    .match(m -> m
                                            .field("content")
                                            .query(queryText)
                                    )
                            )
                            .size(maxResults),
                            Map.class);

                    List<Content> contents = response.hits().hits().stream()
                            .map(hit -> {
                                Map<String, Object> source = hit.source();
                                String text = source != null && source.containsKey("text")
                                        ? (String) source.get("text")
                                        : (source != null ? source.toString() : "");
                                return Content.from(text);
                            })
                            .collect(Collectors.toList());

                    long duration = System.currentTimeMillis() - startTime;
                    log.info("✅ [ES全文检索-完成] 耗时: {}ms, 命中: {} 条", duration, contents.size());
                    
                    // 打印前3条结果的摘要，方便调试
                    if (!contents.isEmpty()) {
                        contents.stream().limit(3).forEach(c -> 
                            log.info("   📄 匹配片段: {}", c.textSegment().text().substring(0, Math.min(50, c.textSegment().text().length())))
                        );
                    }

                    return contents;
                } catch (IOException e) {
                    long duration = System.currentTimeMillis() - startTime;
                    log.error("❌ [ES全文检索-失败] 耗时: {}ms, 错误: {}", duration, e.getMessage(), e);
                    return Collections.emptyList();
                }
            }
        };
    }
}
