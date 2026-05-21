package com.kanade.backend.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kanade.backend.ai.AiChatService;
import com.kanade.backend.ai.AiServiceFactory;
import com.kanade.backend.ai.rag.DocumentRagService;
import com.kanade.backend.constant.SseMessageTypeEnum;
import com.kanade.backend.dto.ChatSessionQueryDTO;
import com.kanade.backend.entity.QaMessage;
import com.kanade.backend.entity.QaSession;
import com.kanade.backend.entity.SessionDocument;
import com.kanade.backend.exception.BusinessException;
import com.kanade.backend.exception.ErrorCode;
import com.kanade.backend.mapper.QaSessionMapper;
import com.kanade.backend.service.QaMessageService;
import com.kanade.backend.service.QaSessionService;
import com.kanade.backend.service.SessionDocumentService;
import com.kanade.backend.utils.GsonUtils;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答会话表 服务层实现。
 *
 * @author kanade
 */
@Slf4j
@Service
public class QaSessionServiceImpl extends ServiceImpl<QaSessionMapper, QaSession> implements QaSessionService {

    @Resource
    private QaMessageService qaMessageService;

    @Resource
    private AiServiceFactory aiServiceFactory;

    @Resource
    private DocumentRagService documentRagService;

    @Resource
    private SessionDocumentService sessionDocumentService;

    @Override
    @Transactional
    public Long createSession(Long userId, String sessionName) {
        // 1. 生成默认名称
        if (sessionName == null || sessionName.trim().isEmpty()) {
            sessionName = "新会话 " + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("MM-dd HH:mm")
            );
        }

        // 2. 创建会话记录
        QaSession session = QaSession.builder()
            .userId(userId)
            .sessionName(sessionName)
            .summary("")
            .createTime(LocalDateTime.now())
            .updateTime(LocalDateTime.now())
            .deleteFlag(0)
            .build();

        this.save(session);
        log.debug("💾 [保存会话] sessionId={}, userId={}, sessionName={}", session.getId(), userId, sessionName);

        return session.getId();
    }

    @Override
    public QaSession createSessionWithDetails(Long userId, String sessionName, String summary) {
        if (sessionName == null || sessionName.trim().isEmpty()) {
            sessionName = "新会话 " + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("MM-dd HH:mm")
            );
        }

        QaSession session = QaSession.builder()
            .userId(userId)
            .sessionName(sessionName)
            .summary(summary != null ? summary : "")
            .createTime(LocalDateTime.now())
            .updateTime(LocalDateTime.now())
            .deleteFlag(0)
            .build();

        this.save(session);
        log.debug("💾 [保存会话] sessionId={}, userId={}, sessionName={}", session.getId(), userId, sessionName);

        return session;
    }

    @Override
    public List<QaSession> listUserSessions(Long userId) {
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("user_id",userId)
                .eq("delete_flag",0)
                .orderBy("update_time desc");
        return this.list(queryWrapper);
    }

    @Override
    public Flux<String> sendMessage(Long sessionId, String userMessage, List<Long> documentIds) {
        // 1. 校验会话存在
        QaSession session = this.getById(sessionId);
        if (session == null || session.getDeleteFlag() == 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }

        // 2. 保存用户消息
        QaMessage userMsg = QaMessage.builder()
            .sessionId(sessionId)
            .messageType(1)
            .content(userMessage)
            .createTime(LocalDateTime.now())
            .deleteFlag(0)
            .build();
        qaMessageService.save(userMsg);

        // 3. 根据是否有参考文档，选择不同的 AI 服务
        AiChatService aiService;
        if (!CollectionUtils.isEmpty(documentIds)) {
            // 保存/更新会话-文档关联
            saveSessionDocuments(sessionId, documentIds, session.getUserId());

            // 确保文档已索引到 ES（副作用：写入 ES）
            documentRagService.getContentRetriever(sessionId, documentIds, session.getUserId());

            aiService = aiServiceFactory.createAdvancedRagChatAssistant(session.getUserId());
            log.info("🚀 [进阶RAG模式] sessionId={}, userId={}, documents={}", sessionId, session.getUserId(), documentIds);
        } else {
            aiService = aiServiceFactory.getChatAssistant();
        }

        // 4. 流式调用 AI 并保存响应
        StringBuilder fullResponse = new StringBuilder();

        return aiService.chat(sessionId, userMessage)
            .map(chunk -> {
                fullResponse.append(chunk);
                return buildStreamingData(SseMessageTypeEnum.STREAMING, chunk);
            })
            .concatWith(Flux.defer(() -> {
                // 5. 保存完整的 AI 消息
                QaMessage aiMsg = QaMessage.builder()
                    .sessionId(sessionId)
                    .messageType(2)
                    .content(fullResponse.toString())
                    .createTime(LocalDateTime.now())
                    .deleteFlag(0)
                    .build();
                qaMessageService.save(aiMsg);

                // 6. 更新会话时间
                session.setUpdateTime(LocalDateTime.now());
                this.updateById(session);

                log.info("✅ [对话完成] sessionId={}, aiResponseLength={}", sessionId, fullResponse.length());

                // 7. 发送完成标记
                return Flux.just(buildCompleteData(sessionId));
            }))
            .onErrorResume(error -> {
                log.error("❌ [AI响应失败] sessionId={}, error={}", sessionId, error.getMessage());
                return Flux.just(buildErrorData(sessionId, error.getMessage()));
            });
    }

    /**
     * 保存会话-文档关联（跳过已存在的记录）
     */
    private void saveSessionDocuments(Long sessionId, List<Long> documentIds, Long userId) {
        try {
            for (Long docId : documentIds) {
                QueryWrapper qw = new QueryWrapper();
                qw.eq("session_id", sessionId);
                qw.eq("document_id", docId);
                if (sessionDocumentService.count(qw) == 0) {
                    SessionDocument sd = SessionDocument.builder()
                            .sessionId(sessionId)
                            .documentId(docId)
                            .createTime(LocalDateTime.now())
                            .build();
                    sessionDocumentService.save(sd);
                }
            }
        } catch (Exception e) {
            log.error("保存会话文档关联失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 加载会话历史消息
     */
    private List<ChatMessage> loadSessionHistory(Long sessionId) {
//        // 1. 查询该会话的所有消息(按时间排序,最多20条)
//        QueryWrapper queryWrapper = new QueryWrapper();
//
//        queryWrapper.where("session_id = ?", sessionId)
//                   .and("delete_flag = ?", 0)
//                   .orderBy("create_time asc")
//                   .limit(20);
//
//        List<QaMessage> history = qaMessageService.list(queryWrapper);
//
//        // 2. 转换为 LangChain4j 的 ChatMessage 格式
//        return history.stream()
//            .map(msg -> msg.getMessageType() == 1
//                ? UserMessage.from(msg.getContent())
//                : AiMessage.from(msg.getContent()))
//            .collect(Collectors.toList());
        return null;
    }

    /**
     * 构建连接成功数据JSON
     *
     * @param sessionId 会话ID
     * @return JSON字符串
     */
    @Override
    public String buildConnectedData(Long sessionId) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "connected");
        data.put("sessionId", sessionId);
        data.put("message", "连接成功");
        return GsonUtils.toJson(data);
    }


    @Override
    public Page<QaSession> listAppChatHistoryByPage(Long id, int pageSize, LocalDateTime lastCreateTime, HttpServletRequest request) {
        ChatSessionQueryDTO chatSessionQueryDTO = new ChatSessionQueryDTO();
        chatSessionQueryDTO.setUserId(id);
        chatSessionQueryDTO.setLastCreateTime(lastCreateTime);
        QueryWrapper queryWrapper = this.getSessionQueryWrapper(chatSessionQueryDTO);
        return this.page(Page.of(1,pageSize),queryWrapper);
    }

    /**
     * 构建流式数据JSON
     *
     * @param type    消息类型
     * @param content 内容
     * @return JSON字符串
     */
    private String buildStreamingData(SseMessageTypeEnum type, String content) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", type.getValue());
        data.put("content", content);
        return GsonUtils.toJson(data);
    }

    /**
     * 构建完成数据JSON
     *
     * @param sessionId 会话ID
     * @return JSON字符串
     */
    private String buildCompleteData(Long sessionId) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", SseMessageTypeEnum.COMPLETE.getValue());
        data.put("sessionId", sessionId);
        data.put("message", "完成");
        return GsonUtils.toJson(data);
    }

    /**
     * 构建错误数据JSON
     *
     * @param sessionId   会话ID
     * @param errorMessage 错误信息
     * @return JSON字符串
     */
    private String buildErrorData(Long sessionId, String errorMessage) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", SseMessageTypeEnum.ERROR.getValue());
        data.put("sessionId", sessionId);
        data.put("message", "错误: " + errorMessage);
        return GsonUtils.toJson(data);
    }

    @Override
    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        QaSession session = this.getById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权删除此会话");
        }

        // 逻辑删除(级联删除消息由数据库外键处理)
        session.setDeleteFlag(1);
        session.setUpdateTime(LocalDateTime.now());
        this.updateById(session);

        log.info("删除会话成功, sessionId={}, userId={}", sessionId, userId);
    }

    @Override
    @Transactional
    public QaSession updateSessionInfo(Long sessionId, Long userId, String sessionName, String summary) {
        QaSession session = this.getById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权修改此会话");
        }

        if (sessionName != null && !sessionName.trim().isEmpty()) {
            session.setSessionName(sessionName);
        }
        if (summary != null) {
            session.setSummary(summary);
        }
        session.setUpdateTime(LocalDateTime.now());
        this.updateById(session);

        log.info("更新会话成功, sessionId={}, userId={}", sessionId, userId);
        return session;
    }


    private QueryWrapper getSessionQueryWrapper(ChatSessionQueryDTO chatSessionQueryDTO) {
        QueryWrapper queryWrapper = QueryWrapper.create();
        if (chatSessionQueryDTO == null) {
            return queryWrapper;
        }
        Long userId = chatSessionQueryDTO.getUserId();
        LocalDateTime lastCreateTime = chatSessionQueryDTO.getLastCreateTime();
        String sortField = chatSessionQueryDTO.getSortField();
        String sortOrder = chatSessionQueryDTO.getSortOrder();
        // 拼接查询条件
        queryWrapper
                .eq("user_id", userId);
        // 游标查询逻辑 - 只使用 createTime 作为游标
        if (lastCreateTime != null) {
            queryWrapper.lt("create_time", lastCreateTime);
        }
        // 排序
        if (StrUtil.isNotBlank(sortField)) {
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        } else {
            // 默认按创建时间降序排列
            queryWrapper.orderBy("create_time", false);
        }
        return queryWrapper;
    }

}
