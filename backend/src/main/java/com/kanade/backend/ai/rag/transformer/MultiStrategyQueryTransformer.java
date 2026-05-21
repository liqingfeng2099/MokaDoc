package com.kanade.backend.ai.rag.transformer;

import com.kanade.backend.ai.rag.prompt.RagPrompts;
import com.kanade.backend.utils.GsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class MultiStrategyQueryTransformer implements QueryTransformer {

    private final ChatModel chatModel;
    private final Map<String, String> queryTargetMap;

    public MultiStrategyQueryTransformer(ChatModel chatModel, Map<String, String> queryTargetMap) {
        this.chatModel = chatModel;
        this.queryTargetMap = queryTargetMap;
    }

    @Override
    public List<Query> transform(Query query) {
        queryTargetMap.clear();

        if (query.metadata().chatMemory() == null ||
            query.metadata().chatMemory().isEmpty()) {
            log.debug("[多策略转换] 无对话历史，跳过转换");
            return List.of(query);
        }

        try {
            long startTime = System.currentTimeMillis();

            String historyText = formatChatHistory(query.metadata().chatMemory());
            String prompt = String.format(RagPrompts.MULTI_STRATEGY_QUERY_TEMPLATE, historyText, query.text());
            String llmResponse = chatModel.chat(prompt);
            log.debug("[LLM原始响应] {}", llmResponse);

            StrategyQueryResult result = parseResponse(llmResponse);
            if (result == null || result.getQueries() == null || result.getQueries().isEmpty()) {
                log.warn("[LLM未返回有效查询] 降级为原始查询");
                return List.of(query);
            }

            List<Query> queries = new ArrayList<>();
            for (StrategyQueryResult.QueryItem item : result.getQueries()) {
                if (item.getText() == null || item.getText().isBlank()) {
                    continue;
                }
                Query q = Query.from(item.getText().trim());
                queryTargetMap.put(q.text(), item.getTarget());
                queries.add(q);
            }

            if (queries.isEmpty()) {
                log.warn("[LLM未返回有效查询] 降级为原始查询");
                return List.of(query);
            }

            long duration = System.currentTimeMillis() - startTime;
            String querySummary = queries.stream()
                    .map(q -> {
                        String target = queryTargetMap.getOrDefault(q.text(), "?");
                        return q.text() + " → " + target;
                    })
                    .collect(Collectors.joining("\n    "));
            log.info("[多策略转换完成] 耗时={}ms, 生成{}个查询:\n    {}",
                    duration, queries.size(), querySummary);

            return queries;

        } catch (Exception e) {
            log.error("[多策略转换失败] 降级返回原查询", e);
            return List.of(query);
        }
    }

    private String formatChatHistory(List<ChatMessage> chatHistory) {
        if (chatHistory == null || chatHistory.isEmpty()) {
            return "（无对话历史）";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : chatHistory) {
            if (message instanceof UserMessage) {
                sb.append("用户: ").append(((UserMessage) message).singleText()).append("\n");
            } else if (message instanceof AiMessage) {
                sb.append("助手: ").append(((AiMessage) message).text()).append("\n");
            }
        }
        return sb.toString();
    }

    private StrategyQueryResult parseResponse(String response) {
        String json = response.trim();
        // 清洗 Markdown 代码块标记
        int jsonStart = json.indexOf('{');
        if (jsonStart > 0) {
            json = json.substring(jsonStart);
        }
        int jsonEnd = json.lastIndexOf('}');
        if (jsonEnd > 0) {
            json = json.substring(0, jsonEnd + 1);
        }
        return GsonUtils.fromJson(json, StrategyQueryResult.class);
    }
}
