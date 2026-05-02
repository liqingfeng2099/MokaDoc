package com.kanade.backend.ai.rag.orchestrator;

import com.kanade.backend.ai.rag.aggregator.ReciprocalRankFusionAggregator;
import com.kanade.backend.ai.rag.injector.PromptTemplateManager;
import com.kanade.backend.ai.rag.injector.TemplateContentInjector;
import com.kanade.backend.ai.rag.router.SmartQueryRouter;
import com.kanade.backend.ai.rag.transformer.ChineseQueryCompressor;
import com.kanade.backend.ai.rag.transformer.EntityBasedExpander;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.rag.query.transformer.DefaultQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class AdvancedRagOrchestrator {

    @Resource
    private ChatModel chatModel;

    @Resource
    private PromptTemplateManager templateManager;

    @Resource
    private ContentRetriever vectorRetriever;

    @Resource
    private ContentRetriever textRetriever;

    @Value("${rag.query-transformation.enabled:true}")
    private boolean queryTransformationEnabled;

    @Value("${rag.query-transformation.type:compress}")
    private String transformationType;

    @Value("${rag.routing.enabled:true}")
    private boolean routingEnabled;

    @Value("${rag.reranking.enabled:true}")
    private boolean rerankingEnabled;

    @Value("${rag.reranking.model:rrf}")
    private String rerankingModel;

    @Value("${rag.reranking.top-k:5}")
    private int rerankTopK;

    @Value("${rag.injection.template:standard}")
    private String injectionTemplate;

    private RetrievalAugmentor retrievalAugmentor;

    @PostConstruct
    public void init() {
        log.info("🚀 [RAG编排器] 初始化进阶RAG组件...");

        QueryTransformer queryTransformer = buildQueryTransformer();
        QueryRouter queryRouter = buildQueryRouter();
        ContentAggregator aggregator = buildAggregator();
        ContentInjector injector = buildInjector();

        retrievalAugmentor = DefaultRetrievalAugmentor.builder()
            .queryTransformer(queryTransformer)
            .queryRouter(queryRouter)
            .contentAggregator(aggregator)
            .contentInjector(injector)
            .build();

        log.info("✅ [RAG编排器] 初始化完成");
    }

    private QueryTransformer buildQueryTransformer() {
        if (!queryTransformationEnabled) {
            log.info("🔧 [查询转换] 已禁用");
            return new DefaultQueryTransformer();
        }

        return switch (transformationType) {
            case "compress" -> {
                log.info("🔧 [查询转换] 使用压缩器");
                yield new ChineseQueryCompressor(chatModel);
            }
            case "expand" -> {
                log.info("🔧 [查询转换] 使用扩展器");
                yield new EntityBasedExpander(3);
            }
            default -> {
                log.warn("⚠️ [查询转换] 未知类型: {}，使用压缩器", transformationType);
                yield new ChineseQueryCompressor(chatModel);
            }
        };
    }

    private Map<String, ContentRetriever> buildRetrieverMap() {
        Map<String, ContentRetriever> map = new HashMap<>();
        map.put("vector", vectorRetriever);
        map.put("text", textRetriever);
        log.info("🔍 [多路检索] 注册了 {} 个检索器: {}", map.size(), map.keySet());
        return map;
    }

    private QueryRouter buildQueryRouter() {
        Map<String, ContentRetriever> retrieverMap = buildRetrieverMap();

        if (!routingEnabled) {
            log.info("🎯 [智能路由] 已禁用，使用默认路由（全部检索器）");
            return new DefaultQueryRouter(retrieverMap.values());
        }
        log.info("🎯 [智能路由] 启用智能路由");
        return new SmartQueryRouter(chatModel, retrieverMap);
    }

    private ContentAggregator buildAggregator() {
        if (!rerankingEnabled) {
            log.info("🔄 [重排序] 已禁用");
            return new DefaultContentAggregator();
        }

        log.info("🔄 [重排序] 使用 RRF 融合");
        return new ReciprocalRankFusionAggregator(60, rerankTopK);
    }

    private ContentInjector buildInjector() {
        log.info("💉 [内容注入] 使用模板: {}", injectionTemplate);
        return templateManager.createInjector(injectionTemplate);
    }

    public RetrievalAugmentor getRetrievalAugmentor() {
        return retrievalAugmentor;
    }
}
