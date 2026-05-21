package com.kanade.backend.ai.rag.injector;

import com.kanade.backend.graph.GraphCrudService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱内容注入器——装饰器模式，在标准内容注入前混入图谱子图上下文。
 *
 * 流程：
 * 1. 从用户消息中提取核心实体名称
 * 2. 在 Neo4j 中查询该实体的子图（1 跳扩展）
 * 3. 将子图信息文本化，插入到参考内容之前
 * 4. 委托给下游 ContentInjector 完成最终注入
 *
 * 当图谱不可用时，自动降级——直接委托下游注入器。
 *
 * @author kanade
 */
@Slf4j
public class GraphContentInjector implements ContentInjector {

    private final ContentInjector delegate;
    private final GraphCrudService graphCrudService;
    private final Long userId;

    /**
     * @param delegate         下游注入器（如 TemplateContentInjector）
     * @param graphCrudService 图谱 CRUD 服务
     * @param userId           用户 ID
     */
    public GraphContentInjector(ContentInjector delegate,
                                 GraphCrudService graphCrudService,
                                 Long userId) {
        this.delegate = delegate;
        this.graphCrudService = graphCrudService;
        this.userId = userId;
    }

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        // 阶段 1：尝试获取图谱子图上下文
        String graphContext = null;
        try {
            graphContext = buildGraphContext(chatMessage, contents);
        } catch (Exception e) {
            log.warn("⚠️ [GraphContentInjector] 图谱查询失败，降级为纯 RAG: {}", e.getMessage());
        }

        // 阶段 2：如果有图谱信息，添加到 Content 列表前面
        if (graphContext != null && !graphContext.isEmpty()) {
            List<Content> enriched = new java.util.ArrayList<>(contents);
            enriched.add(0, Content.from(graphContext));
            log.info("💉 [图谱注入] 已混入图谱子图上下文, contentLength={}", graphContext.length());
            return delegate.inject(enriched, chatMessage);
        }

        // 阶段 3：无图谱信息，直接委托
        return delegate.inject(contents, chatMessage);
    }

    /**
     * 构建图谱上下文文本。
     */
    private String buildGraphContext(ChatMessage chatMessage, List<Content> contents) {
        String questionText = chatMessage instanceof UserMessage
            ? ((UserMessage) chatMessage).singleText()
            : chatMessage.toString();

        if (questionText == null || questionText.isBlank()) {
            return null;
        }

        // 从已有 RAG 内容中提取实体名
        String entityName = extractMainEntity(questionText, contents);
        if (entityName == null) {
            return null;
        }

        // 查询图谱子图
        Map<String, Object> subgraph = graphCrudService.expandSubgraph(entityName, userId, 1);
        if (subgraph == null || subgraph.isEmpty()) {
            log.debug("🔍 [图谱注入] 未找到实体 '{}' 的子图", entityName);
            return null;
        }

        return formatGraphContext(entityName, subgraph);
    }

    /**
     * 从问题文本和已有 RAG 内容中提取核心实体名。
     * 策略：取问题中的前几个名词短语或已有 RAG 内容中提及的实体名。
     */
    private String extractMainEntity(String question, List<Content> contents) {
        // 简单策略：从问题中取引号内的文本，或者问题中的关键名词
        // 先尝试从已有 Content 中提取提及的实体
        for (Content content : contents) {
            String text = content.textSegment().text();
            // 尝试从图谱信息标记中提取实体名
            if (text.contains("【知识图谱信息 - 相关实体：")) {
                int start = text.indexOf("：") + 1;
                int end = text.indexOf("】", start);
                if (end > start) {
                    return text.substring(start, end).trim();
                }
            }
        }

        // 从问题中提取：取前 30 个字符中的核心名词
        String shortQuestion = question.length() > 60 ? question.substring(0, 60) : question;
        // 去除问句前缀
        shortQuestion = shortQuestion.replaceAll("^(什么是|请解释|介绍一下|告诉我关于|什么是关于)", "").trim();

        // 尝试在 Neo4j 中搜索最匹配的前 2 个词
        String[] words = shortQuestion.split("[，。？\\s]+");
        if (words.length > 0) {
            // 取第一个较长的词（>2字）作为候选实体名
            for (String word : words) {
                if (word.length() >= 2 && !word.matches(".*[是什么怎么哪些如何为什么].*")) {
                    return word.trim();
                }
            }
        }

        return shortQuestion.length() >= 2 ? shortQuestion : null;
    }

    /**
     * 格式化图谱上下文为可注入的文本。
     */
    private String formatGraphContext(String entityName, Map<String, Object> subgraph) {
        StringBuilder sb = new StringBuilder();
        sb.append("【知识图谱信息 - 相关实体：").append(entityName).append("】\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>)
            subgraph.getOrDefault("nodes", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>)
            subgraph.getOrDefault("edges", List.of());

        if (!nodes.isEmpty()) {
            sb.append("关联实体：");
            int count = 0;
            for (Map<String, Object> node : nodes) {
                if (count++ >= 10) break;
                Object name = node.get("name");
                Object type = node.get("type");
                if (name != null && !name.equals(entityName)) {
                    sb.append(name).append("(").append(type != null ? type : "?").append("), ");
                }
            }
            if (sb.charAt(sb.length() - 2) == ',') {
                sb.setLength(sb.length() - 2);
            }
            sb.append("\n");
        }

        if (!edges.isEmpty()) {
            sb.append("关系类型：");
            Set<String> edgeTypes = new java.util.LinkedHashSet<>();
            for (Map<String, Object> edge : edges) {
                Object type = edge.get("type");
                if (type != null) edgeTypes.add(type.toString());
            }
            sb.append(String.join(", ", edgeTypes));
            sb.append("\n");
        }

        return sb.toString();
    }
}
