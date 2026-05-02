package com.kanade.backend.ai.rag.transformer;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class ChineseQueryCompressor implements QueryTransformer {

    private final ChatModel chatModel;

    public ChineseQueryCompressor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public List<Query> transform(Query query) {
        if (query.metadata().chatMemory() == null ||
            query.metadata().chatMemory().isEmpty()) {
            log.debug("🔧 [查询压缩] 无对话历史，跳过压缩");
            return List.of(query);
        }

        try {
            String compressedQuery = compressQuery(query, query.metadata().chatMemory());
            Query transformedQuery = Query.from(compressedQuery);
            log.info("🔧 [查询压缩] 原始: '{}' → 压缩: '{}'",
                query.text(), compressedQuery);
            return List.of(transformedQuery);
        } catch (Exception e) {
            log.error("❌ [查询压缩失败] 降级返回原查询", e);
            return List.of(query);
        }
    }

    private String compressQuery(Query query, List<ChatMessage> chatHistory) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个查询优化助手。请将用户的最新问题结合对话历史，重写为一个独立的、完整的问题。\n\n");
        prompt.append("要求：\n");
        prompt.append("1. 消除代词（如'它'、'这个'），替换为具体实体\n");
        prompt.append("2. 保持原问题的核心意图\n");
        prompt.append("3. 只输出重写后的问题，不要添加任何解释\n\n");

        prompt.append("对话历史：\n");
        for (ChatMessage message : chatHistory) {
            if (message instanceof UserMessage) {
                prompt.append("用户: ").append(((UserMessage) message).singleText()).append("\n");
            } else if (message instanceof AiMessage) {
                prompt.append("助手: ").append(((AiMessage) message).text()).append("\n");
            }
        }

        prompt.append("\n最新问题: ").append(query.text()).append("\n");
        prompt.append("\n重写后的问题: ");

        String compressed = chatModel.chat(prompt.toString());
        return compressed.trim().replaceAll("^\"|\"$", "").trim();
    }
}
