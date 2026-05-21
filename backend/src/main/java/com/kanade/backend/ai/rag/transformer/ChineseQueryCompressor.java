package com.kanade.backend.ai.rag.transformer;

import static com.kanade.backend.ai.rag.prompt.RagPrompts.QUERY_COMPRESSION_TEMPLATE;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 中文查询压缩转换器
 * 
 * <p>核心作用：将多轮对话中依赖上下文的"模糊查询"重写为"独立完整的问题"，
 * 从而提升向量检索的召回率和准确率。
 * 
 * <p>工作原理：
 * 1. 检测是否存在对话历史，无历史则直接返回原查询（零开销）
 * 2. 从 {@link RagPrompts} 获取压缩提示词模板
 * 3. 调用 LLM 进行指代消解和上下文补全
 * 4. 返回重写后的独立查询用于后续检索
 * 
 * <p>示例：
 * - 原始问题："他还在世吗？"（依赖上下文）
 * - 压缩后："Guido van Rossum还在世吗？"（独立完整）
 * 
 * @author kanade
 */
@Slf4j
public class ChineseQueryCompressor implements QueryTransformer {

    /**
     * 用于执行查询重写的聊天模型
     */
    private final ChatModel chatModel;

    /**
     * 构造函数
     * 
     * @param chatModel 聊天模型实例，用于调用 LLM 进行查询压缩
     */
    public ChineseQueryCompressor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public List<Query> transform(Query query) {
        // 快速路径：无对话历史时跳过压缩，直接返回原查询
        if (query.metadata().chatMemory() == null ||
            query.metadata().chatMemory().isEmpty()) {
            log.debug("🔧 [查询压缩] 无对话历史，跳过压缩");
            return List.of(query);
        }

        try {
            // 调用 LLM 进行查询压缩
            String compressedQuery = compressQuery(query, query.metadata().chatMemory());
            Query transformedQuery = Query.from(compressedQuery);
            
            // 详细记录转换前后对比
            log.info("🔧 [查询压缩] 转换详情:\n  原始查询: {}\n  压缩查询: {}",
                query.text(), compressedQuery);
            
            return List.of(transformedQuery);
        } catch (Exception e) {
            // 降级策略：压缩失败时返回原查询，避免中断 RAG 流程
            log.error("❌ [查询压缩失败] 降级返回原查询", e);
            return List.of(query);
        }
    }

    /**
     * 执行查询压缩：结合对话历史重写最新问题
     * 
     * <p>使用 {@link RagPrompts#QUERY_COMPRESSION_TEMPLATE} 统一管理提示词
     * 
     * @param query 最新查询对象
     * @param chatHistory 对话历史消息列表
     * @return 重写后的独立查询字符串
     */
    private String compressQuery(Query query, List<ChatMessage> chatHistory) {
        // 格式化对话历史
        String historyText = formatChatHistory(chatHistory);
        
        // 从常量类获取提示词模板并填充
        String prompt = String.format(QUERY_COMPRESSION_TEMPLATE, historyText, query.text());

        // 调用 LLM 生成
        String compressed = chatModel.chat(prompt);
        
        // 清理输出（去除可能的引号、空格）
        return compressed.trim().replaceAll("^\"|\"$", "").trim();
    }

    /**
     * 格式化对话历史为文本
     * 
     * @param chatHistory 对话历史消息列表
     * @return 格式化后的文本
     */
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
}
