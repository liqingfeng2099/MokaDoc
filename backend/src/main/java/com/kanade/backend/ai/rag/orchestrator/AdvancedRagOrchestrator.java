package com.kanade.backend.ai.rag.orchestrator;

import com.kanade.backend.ai.rag.aggregator.ReciprocalRankFusionAggregator;
import com.kanade.backend.ai.rag.injector.GraphContentInjector;
import com.kanade.backend.ai.rag.injector.PromptTemplateManager;
import com.kanade.backend.ai.rag.injector.TemplateContentInjector;
import com.kanade.backend.ai.rag.router.SmartQueryRouter;
import com.kanade.backend.ai.rag.transformer.ChineseQueryCompressor;
import com.kanade.backend.ai.rag.transformer.EntityBasedExpander;
import com.kanade.backend.graph.CypherTemplateEngine;
import com.kanade.backend.graph.GraphContentRetriever;
import com.kanade.backend.graph.GraphCrudService;
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

/**
 * 进阶 RAG 编排器——扩展版（支持 Graph RAG 混合）。
 *
 * 相比原版变更：
 * 1. buildRetrieverMap() 注册 graph 检索器（如果启用）
 * 2. buildInjector() 使用 GraphContentInjector 包裹原注入器
 * 3. 通过配置控制图谱功能开关
 *
 * @author kanade
 */
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

    @Resource
    private GraphCrudService graphCrudService;

    @Resource
    private CypherTemplateEngine cypherTemplateEngine;

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

    // ====== 图谱 RAG 新增配置 ======

    @Value("${rag.graph.enabled:true}")
    private boolean graphEnabled;

    @Value("${rag.graph.max-results:5}")
    private int graphMaxResults;

    private RetrievalAugmentor retrievalAugmentor;

    /**
     * 当前查询的用户 ID（在 createAdvancedRagAugmentor 时注入）。
     * 设为 ThreadLocal 或通过工厂方法传递。
     */
    private Long currentUserId;

    @PostConstruct
    public void init() {
        // @PostConstruct 时 userId 尚未确定，仅做基础初始化
        log.info("🚀 [RAG编排器] 初始化进阶RAG组件...");
        log.info("📊 [图谱RAG] graphEnabled={}, graphMaxResults={}", graphEnabled, graphMaxResults);
    }

    /**
     * 为指定用户创建 RAG 增强器（每次会话调用时重新构建）。
     * 因为 userId 在运行时才知道，所以不在 @PostConstruct 中构建。
     */
    public RetrievalAugmentor createAdvancedRagAugmentor(Long userId) {
        this.currentUserId = userId;
        log.info("🚀 [RAG编排器] 为用户 {} 创建 RAG 增强器", userId);

        QueryTransformer queryTransformer = buildQueryTransformer();
        QueryRouter queryRouter = buildQueryRouter();
        ContentAggregator aggregator = buildAggregator();
        ContentInjector injector = buildInjector(userId);

        retrievalAugmentor = DefaultRetrievalAugmentor.builder()
            .queryTransformer(queryTransformer)
            .queryRouter(queryRouter)
            .contentAggregator(aggregator)
            .contentInjector(injector)
            .build();

        log.info("✅ [RAG编排器] 创建完成");
        return retrievalAugmentor;
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

    /**
     * 构建检索器映射——扩展版：增加 graph 检索器。
     */
    private Map<String, ContentRetriever> buildRetrieverMap() {
        Map<String, ContentRetriever> map = new HashMap<>();
        map.put("vector", vectorRetriever);
        map.put("text", textRetriever);

        // 如果启用图谱 RAG，注册 graph 检索器
        if (graphEnabled && currentUserId != null) {
            ContentRetriever graphRetriever = new GraphContentRetriever(
                graphCrudService, cypherTemplateEngine, graphMaxResults, currentUserId);
            map.put("graph", graphRetriever);
            log.info("🔍 [多路检索] 注册了 {} 个检索器（含 graph）: {}", map.size(), map.keySet());
        } else {
            log.info("🔍 [多路检索] 注册了 {} 个检索器（无 graph）: {}", map.size(), map.keySet());
        }

        return map;
    }

    private QueryRouter buildQueryRouter() {
        Map<String, ContentRetriever> retrieverMap = buildRetrieverMap();

        if (!routingEnabled) {
            log.info("🎯 [智能路由] 已禁用，使用默认路由（全部检索器）");
            return new DefaultQueryRouter(retrieverMap.values());
        }
        log.info("🎯 [智能路由] 启用智能路由（含 GRAPH/HYBRID 意图）");
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

    /**
     * 构建注入器——扩展版：使用 GraphContentInjector 装饰。
     */
    private ContentInjector buildInjector(Long userId) {
        log.info("💉 [内容注入] 使用模板: {}", injectionTemplate);

        // 基础注入器
        ContentInjector baseInjector = templateManager.createInjector(injectionTemplate);

        // 如果启用图谱 RAG，用 GraphContentInjector 装饰
        if (graphEnabled && userId != null) {
            log.info("💉 [内容注入] 已启用 GraphContentInjector 装饰器");
            return new GraphContentInjector(baseInjector, graphCrudService, userId);
        }

        return baseInjector;
    }

    public RetrievalAugmentor getRetrievalAugmentor() {
        return retrievalAugmentor;
    }
}
