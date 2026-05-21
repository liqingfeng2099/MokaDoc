package com.kanade.backend.ai.memory;

import com.kanade.backend.utils.GsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Redis聊天记忆存储服务（可选）
 * 提供高速缓存层，减少数据库查询压力
 * 
 * 注意：如果Redis不可用，会自动降级到纯数据库模式
 */
@Slf4j
@Component
public class RedisMemoryStore {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * Redis Key前缀
     * 格式: chat:memory:{sessionId}
     */
    private static final String MEMORY_KEY_PREFIX = "chat:memory:";

    /**
     * 缓存过期时间（30分钟）
     * 如果30分钟内没有新的对话，自动清除缓存
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /**
     * 从Redis获取会话记忆
     *
     * @param sessionId 会话ID
     * @return ChatMessage列表，如果缓存未命中或Redis不可用返回null
     */
    public List<ChatMessage> getMessages(Long sessionId) {
        // 检查Redis是否可用
        if (redisTemplate == null) {
            log.debug("⚠️ [Redis未配置] 跳过缓存读取，直接使用数据库");
            return null;
        }

        String key = buildKey(sessionId);
        
        try {
            // 从Redis List中获取所有消息（JSON字符串）
            List<String> jsonMessages = redisTemplate.opsForList().range(key, 0, -1);
            
            if (jsonMessages == null || jsonMessages.isEmpty()) {
                log.debug("🔴 [Redis未命中] sessionId={}", sessionId);
                return null; // 缓存未命中
            }

            // 反序列化为ChatMessage对象
            List<ChatMessage> messages = jsonMessages.stream()
                .map(this::deserializeMessage)
                .collect(Collectors.toList());

            log.debug("🟢 [Redis命中] sessionId={}, messageCount={}", sessionId, messages.size());
            return messages;
            
        } catch (Exception e) {
            log.warn("⚠️ [Redis读取失败] sessionId={}, error={}, 降级到数据库", sessionId, e.getMessage());
            return null; // 出错时返回null，降级到数据库查询
        }
    }

    /**
     * 保存会话记忆到Redis
     *
     * @param sessionId 会话ID
     * @param messages  ChatMessage列表
     */
    public void saveMessages(Long sessionId, List<ChatMessage> messages) {
        // 检查Redis是否可用
        if (redisTemplate == null || messages == null || messages.isEmpty()) {
            return;
        }

        String key = buildKey(sessionId);
        
        try {
            // 序列化为JSON字符串列表
            List<String> jsonMessages = messages.stream()
                .map(this::serializeMessage)
                .collect(Collectors.toList());

            // 删除旧数据（避免累积）
            redisTemplate.delete(key);
            
            // 批量写入Redis List
            redisTemplate.opsForList().rightPushAll(key, jsonMessages);
            
            // 设置过期时间
            redisTemplate.expire(key, CACHE_TTL);

            log.debug("💾 [Redis写入成功] sessionId={}, messageCount={}, ttl={}min", 
                     sessionId, messages.size(), CACHE_TTL.toMinutes());
            
        } catch (Exception e) {
            log.warn("⚠️ [Redis写入失败] sessionId={}, error={}, 不影响主流程", sessionId, e.getMessage());
            // Redis写入失败不影响主流程，只记录日志
        }
    }

    /**
     * 追加单条消息到Redis记忆
     *
     * @param sessionId 会话ID
     * @param message   新消息
     */
    public void appendMessage(Long sessionId, ChatMessage message) {
        // 检查Redis是否可用
        if (redisTemplate == null) {
            return;
        }

        // SystemMessage 不需要持久化，它是静态配置，每次对话都会重新注入
        if (message instanceof SystemMessage) {
            log.debug("⏭️ [跳过SystemMessage] sessionId={}, reason=系统提示词无需持久化", sessionId);
            return;
        }

        String key = buildKey(sessionId);
        
        try {
            String jsonMessage = serializeMessage(message);
            redisTemplate.opsForList().rightPush(key, jsonMessage);
            
            // 刷新过期时间
            redisTemplate.expire(key, CACHE_TTL);
            
            log.debug("➕ [Redis追加消息] sessionId={}, messageType={}", 
                     sessionId, 
                     message instanceof UserMessage ? "user" : "ai");
            
        } catch (Exception e) {
            log.warn("⚠️ [Redis追加失败] sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 删除会话记忆缓存
     *
     * @param sessionId 会话ID
     */
    public void deleteMemory(Long sessionId) {
        // 检查Redis是否可用
        if (redisTemplate == null) {
            return;
        }

        String key = buildKey(sessionId);
        
        try {
            Boolean deleted = redisTemplate.delete(key);
            log.info("🗑️ [Redis删除记忆] sessionId={}, success={}", sessionId, deleted);
            
        } catch (Exception e) {
            log.warn("⚠️ [Redis删除失败] sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 构建Redis Key
     */
    private String buildKey(Long sessionId) {
        return MEMORY_KEY_PREFIX + sessionId;
    }

    /**
     * 序列化ChatMessage为JSON字符串
     *
     * 格式: {"type":"user","content":"你好"} 或 {"type":"ai","content":"你好！"}
     * 注意：SystemMessage 不会调用此方法（已在 appendMessage 中过滤）
     */
    private String serializeMessage(ChatMessage message) {
        if (message instanceof UserMessage) {
            return GsonUtils.toJson(new MessageWrapper("user", ((UserMessage) message).singleText()));
        } else if (message instanceof AiMessage) {
            return GsonUtils.toJson(new MessageWrapper("ai", ((AiMessage) message).text()));
        } else {
            log.error("❌ [不支持的消息类型] class={}, 这不应该发生，请检查调用链", message.getClass().getName());
            throw new IllegalArgumentException("Unsupported message type: " + message.getClass());
        }
    }

    /**
     * 反序列化JSON字符串为ChatMessage
     */
    private ChatMessage deserializeMessage(String json) {
        MessageWrapper wrapper = GsonUtils.fromJson(json, MessageWrapper.class);
        
        if ("user".equals(wrapper.getType())) {
            return UserMessage.from(wrapper.getContent());
        } else if ("ai".equals(wrapper.getType())) {
            return AiMessage.from(wrapper.getContent());
        } else {
            throw new IllegalArgumentException("Unknown message type: " + wrapper.getType());
        }
    }

    /**
     * 消息包装类（用于JSON序列化）
     */
    private static class MessageWrapper {
        private String type;    // "user" 或 "ai"
        private String content; // 消息内容

        public MessageWrapper() {}

        public MessageWrapper(String type, String content) {
            this.type = type;
            this.content = content;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
