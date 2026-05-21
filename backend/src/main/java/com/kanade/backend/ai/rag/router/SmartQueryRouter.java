package com.kanade.backend.ai.rag.router;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能查询路由器——扩展版。
 * 
 * 在原有 SEMANTIC / KEYWORD / RELATION / COMPLEX 基础上，
 * 新增 GRAPH（纯图谱）和 HYBRID（Graph RAG 混合）意图。
 *
 * 路由策略：
 * - 关系型/实体型问题 → GRAPH 或 HYBRID
 * - 语义型问题 → SEMANTIC（向量检索）
 * - 关键词精确匹配 → KEYWORD（全文检索）
 * - 图谱不可用时自动降级 → 全部检索器
 *
 * @author kanade
 */
@Slf4j
public class SmartQueryRouter implements QueryRouter {

    private final ChatModel chatModel;
    private final Map<String, ContentRetriever> retrievers;

    /**
     * 图谱检索器是否可用
     */
    private boolean graphAvailable = false;

    public SmartQueryRouter(ChatModel chatModel,
                            Map<String, ContentRetriever> retrievers) {
        this.chatModel = chatModel;
        this.retrievers = retrievers;
        this.graphAvailable = retrievers.containsKey("graph");
    }

    @Override
    public Collection<ContentRetriever> route(Query query) {
        try {
            String intent = analyzeIntent(query.text());
            log.info("🎯 [智能路由] query='{}', intent={}", query.text(), intent);

            Collection<ContentRetriever> selectedRetrievers = selectRetrievers(intent);

            log.info("🎯 [路由决策] 选择了 {} 个检索器: {}",
                selectedRetrievers.size(),
                selectedRetrievers.stream()
                    .map(r -> r.getClass().getSimpleName())
                    .collect(Collectors.joining(", ")));

            return selectedRetrievers;
        } catch (Exception e) {
            log.error("❌ [路由失败] 降级使用全部检索器", e);
            return new ArrayList<>(retrievers.values());
        }
    }

    /**
     * 分析用户查询意图（扩展版——新增 GRAPH 和 HYBRID）。
     */
    private String analyzeIntent(String query) {
        String prompt = String.format("""
            请分析以下用户查询的意图类型，从以下选项中选择最匹配的一个：

            选项：
            - SEMANTIC: 语义相似性查询（如"什么是XXX"、"XXX的特点"）
            - KEYWORD: 关键词精确匹配（如"XXX的定义"、"XXX的版本号"）
            - RELATION: 实体关系查询（如"XXX和YYY的关系"、"XXX的影响"）
            - GRAPH: 需要知识图谱回答的实体关联问题（如"XXX参与了哪些项目"、"XXX和谁有关"）
            - HYBRID: 需要同时结合文档内容和实体关系的问题（如"XXX在项目中扮演什么角色"）
            - COMPLEX: 复杂综合查询（需要多角度信息）

            用户查询：%s

            意图类型（只输出选项名称）：""", query);

        String intent = chatModel.chat(prompt).trim().toUpperCase();

        if (!Arrays.asList("SEMANTIC", "KEYWORD", "RELATION", "COMPLEX", "GRAPH", "HYBRID")
                .contains(intent)) {
            log.warn("⚠️ [意图识别] 未知意图: {}，默认为 SEMANTIC", intent);
            return "SEMANTIC";
        }

        return intent;
    }

    /**
     * 根据意图选择检索器（扩展版——新增 GRAPH 和 HYBRID 分支）。
     */
    private Collection<ContentRetriever> selectRetrievers(String intent) {
        List<ContentRetriever> selected = new ArrayList<>();

        switch (intent) {
            case "SEMANTIC" -> {
                if (retrievers.containsKey("vector")) selected.add(retrievers.get("vector"));
            }
            case "KEYWORD" -> {
                if (retrievers.containsKey("text")) selected.add(retrievers.get("text"));
            }
            case "GRAPH" -> {
                // 纯图谱：只走图检索，降级到 vector
                if (graphAvailable && retrievers.containsKey("graph")) {
                    selected.add(retrievers.get("graph"));
                } else if (retrievers.containsKey("vector")) {
                    log.warn("⚠️ [图谱不可用] 降级到 vector 检索");
                    selected.add(retrievers.get("vector"));
                }
            }
            case "HYBRID" -> {
                // Graph RAG 混合：同时走图谱 + 向量检索
                if (graphAvailable && retrievers.containsKey("graph")) {
                    selected.add(retrievers.get("graph"));
                }
                if (retrievers.containsKey("vector")) {
                    selected.add(retrievers.get("vector"));
                }
                if (retrievers.containsKey("text")) {
                    selected.add(retrievers.get("text"));
                }
            }
            case "RELATION", "COMPLEX" -> {
                // 关系和复杂查询：走全部检索器（含 graph 如果可用）
                if (graphAvailable && retrievers.containsKey("graph")) {
                    selected.add(retrievers.get("graph"));
                }
                selected.addAll(retrievers.values().stream()
                    .filter(r -> !"graph".equals(getRetrieverKey(r)))
                    .collect(Collectors.toList()));
            }
        }

        // 兜底：至少返回一个检索器
        if (selected.isEmpty() && retrievers.containsKey("vector")) {
            selected.add(retrievers.get("vector"));
        }

        return selected;
    }

    /**
     * 获取检索器在 Map 中的 key。
     */
    private String getRetrieverKey(ContentRetriever retriever) {
        return retrievers.entrySet().stream()
            .filter(e -> e.getValue() == retriever)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse("unknown");
    }
}
