package com.kanade.backend.ai;

import com.kanade.backend.ai.rag.orchestrator.AdvancedRagOrchestrator;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI服务工厂
 * 根据模型类型提供对应的AI服务实例
 */
@Slf4j
@Component
public class AiServiceFactory {

    @Resource
    private OpenAiStreamingChatModel openAiStreamingChatModel;

    @Resource
    private ChatMemoryProvider chatMemoryProvider;

    @Resource
    private AdvancedRagOrchestrator advancedRagOrchestrator;
    
    /**
     * 缓存已创建的AI服务实例，避免重复创建
     */
    private final Map<AiModelType, Object> serviceCache = new ConcurrentHashMap<>();
    
    /**
     * 获取指定类型的AI服务
     *
     * @param modelType AI模型类型
     * @return 对应的AI服务实例
     * @throws IllegalArgumentException 如果模型类型不支持
     */
    @SuppressWarnings("unchecked")
    public <T> T getService(AiModelType modelType) {
        if (modelType == null) {
            throw new IllegalArgumentException("AI模型类型不能为空");
        }
        
        // 从缓存中获取
        return (T) serviceCache.computeIfAbsent(modelType, this::createService);
    }
    
    /**
     * 获取通用聊天助手服务
     *
     * @return AiChatService实例
     */
    public AiChatService getChatAssistant() {
        return getService(AiModelType.CHAT_ASSISTANT);
    }
    
    /**
     * 获取文档分析专家服务
     *
     * @return DocumentAnalystService实例
     */
    public DocumentAnalystService getDocumentAnalyst() {
        return getService(AiModelType.DOCUMENT_ANALYST);
    }
        

    
    /**
     * 根据模型类型创建对应的AI服务（使用Builder模式配置ChatMemoryProvider）
     *
     * @param modelType AI模型类型
     * @return AI服务实例
     */
    private Object createService(AiModelType modelType) {
        log.info("🏭 [创建AI服务] type={}, description={}", modelType.getCode(), modelType.getDescription());
        
        return switch (modelType) {
            case CHAT_ASSISTANT -> {
                log.debug("🧠 [配置记忆] 为CHAT_ASSISTANT启用ChatMemoryProvider");
                yield AiServices.builder(AiChatService.class)
                    .streamingChatModel(openAiStreamingChatModel)
                    .chatMemoryProvider(chatMemoryProvider)  // 关键：配置记忆提供者
                    .build();
            }
            
            case DOCUMENT_ANALYST -> {
                log.debug("📄 [无记忆] DOCUMENT_ANALYST不需要记忆功能");
                yield AiServices.create(DocumentAnalystService.class, openAiStreamingChatModel);
            }
            
//            case CODE_ASSISTANT ->
//                AiServices.create(CodeAssistantService.class, openAiStreamingChatModel);
//
//            case TRANSLATION_ASSISTANT ->
//                AiServices.create(TranslationAssistantService.class, openAiStreamingChatModel);
//
            default -> 
                throw new IllegalArgumentException("不支持的AI模型类型: " + modelType.getCode());
        };
    }
    
    /**
     * 创建带文档 RAG 的聊天助手（不缓存，每次调用新建）
     *
     * @param contentRetriever 文档内容检索器
     * @return AiChatService 实例
     */
    public AiChatService createRagChatAssistant(ContentRetriever contentRetriever) {
        log.info("🧠 [创建RAG助手] 已配置 ContentRetriever");
        return AiServices.builder(AiChatService.class)
                .streamingChatModel(openAiStreamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .contentRetriever(contentRetriever)
                .build();
    }

    /**
     * 创建进阶 RAG 聊天助手（使用 DefaultRetrievalAugmentor 管道，含图谱 RAG）。
     *
     * @param userId 当前用户 ID（用于图谱检索的 userId 隔离）
     */
    public AiChatService createAdvancedRagChatAssistant(Long userId) {
        log.info("🚀 [创建进阶RAG助手] 已配置 RetrievalAugmentor（含图谱）, userId={}", userId);
        return AiServices.builder(AiChatService.class)
                .streamingChatModel(openAiStreamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .retrievalAugmentor(advancedRagOrchestrator.createAdvancedRagAugmentor(userId))
                .build();
    }

    /**
     * 创建进阶 RAG 聊天助手（无图谱模式，兼容旧调用）。
     */
    public AiChatService createAdvancedRagChatAssistant() {
        log.info("🚀 [创建进阶RAG助手] 已配置 RetrievalAugmentor（无图谱）");
        return AiServices.builder(AiChatService.class)
                .streamingChatModel(openAiStreamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .retrievalAugmentor(advancedRagOrchestrator.getRetrievalAugmentor())
                .build();
    }

    /**
     * 清除缓存的服务实例（用于测试或重新加载）
     */
    public void clearCache() {
        serviceCache.clear();
        log.info("AI服务缓存已清除");
    }
}
