package com.kanade.backend.controller;

import com.kanade.backend.common.BaseResponse;
import com.kanade.backend.common.ResultUtils;
import com.kanade.backend.entity.GraphTask;
import com.kanade.backend.entity.User;
import com.kanade.backend.exception.BusinessException;
import com.kanade.backend.exception.ErrorCode;
import com.kanade.backend.graph.CypherTemplateEngine;
import com.kanade.backend.graph.GraphBuildOrchestrator;
import com.kanade.backend.graph.GraphCrudService;
import com.kanade.backend.graph.model.GraphEntity;
import com.kanade.backend.mapper.GraphTaskMapper;
import com.kanade.backend.sse.SseEmitterManager;
import com.mybatisflex.core.query.QueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱管理控制器。
 * 提供图谱构建、查询、删除等 REST API。
 *
 * @author kanade
 */
@Slf4j
@RestController
@RequestMapping("/graph")
@Tag(name = "图谱管理", description = "知识图谱构建、查询与管理")
public class GraphController {

    @Resource
    private GraphBuildOrchestrator orchestrator;

    @Resource
    private GraphCrudService graphCrudService;

    @Resource
    private GraphTaskMapper graphTaskMapper;

    @Resource
    private CypherTemplateEngine cypherTemplateEngine;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Resource
    private HttpServletRequest request;

    /**
     * 触发图谱构建。
     */
    @PostMapping("/build")
    @Operation(summary = "触发图谱构建", description = "异步构建知识图谱，通过 SSE 推送进度")
    public BaseResponse<Map<String, Object>> build(@RequestBody(required = false) Map<String, Object> body) {
        Long userId = getLoginUserId();

        // 解析选中文档 ID 列表
        @SuppressWarnings("unchecked")
        List<Integer> docIdInts = body != null && body.containsKey("documentIds")
            ? (List<Integer>) body.get("documentIds")
            : List.of();
        List<Long> documentIds = docIdInts.stream()
            .map(Long::valueOf)
            .toList();

        // 创建任务记录
        GraphTask task = GraphTask.builder()
            .userId(userId)
            .status("PENDING")
            .documentIds(documentIds.isEmpty() ? null : documentIds.toString())
            .progress(0)
            .createTime(LocalDateTime.now())
            .updateTime(LocalDateTime.now())
            .deleteFlag(0)
            .build();
        graphTaskMapper.insert(task);

        // 异步执行构建
        orchestrator.buildAsync(task.getId(), userId, documentIds);

        log.info("📋 [图谱构建] taskId={}, userId={}, docCount={}", task.getId(), userId, documentIds.size());

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getId());
        result.put("status", "PENDING");
        return ResultUtils.success(result);
    }

    /**
     * SSE 订阅构建进度。
     */
    @GetMapping(value = "/build/progress/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "构建进度推送", description = "SSE 实时推送图谱构建进度")
    public SseEmitter progress(@PathVariable Long taskId) {
        Long userId = getLoginUserId();

        // 验证任务归属
        GraphTask task = graphTaskMapper.selectOneById(taskId);
        if (task == null || !task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问此任务");
        }

        return sseEmitterManager.createEmitter(taskId.toString());
    }

    /**
     * 查询构建任务列表。
     */
    @GetMapping("/tasks")
    @Operation(summary = "构建任务列表", description = "获取当前用户的图谱构建任务")
    public BaseResponse<List<GraphTask>> listTasks() {
        Long userId = getLoginUserId();
        QueryWrapper qw = new QueryWrapper();
        qw.eq("user_id", userId)
            .eq("delete_flag", 0)
            .orderBy("create_time", false);
        List<GraphTask> tasks = graphTaskMapper.selectListByQuery(qw);
        return ResultUtils.success(tasks);
    }

    /**
     * 查询单个任务。
     */
    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "任务详情", description = "获取指定构建任务的状态")
    public BaseResponse<GraphTask> getTask(@PathVariable Long taskId) {
        Long userId = getLoginUserId();
        GraphTask task = graphTaskMapper.selectOneById(taskId);
        if (task == null || !task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "任务不存在");
        }
        return ResultUtils.success(task);
    }

    /**
     * 图谱实体搜索。
     */
    @GetMapping("/search")
    @Operation(summary = "实体搜索", description = "按名称搜索图谱中的实体")
    public BaseResponse<List<GraphEntity>> search(@RequestParam String keyword,
                                                   @RequestParam(defaultValue = "20") int limit) {
        Long userId = getLoginUserId();
        List<GraphEntity> entities = graphCrudService.searchEntities(keyword, userId, limit);
        return ResultUtils.success(entities);
    }

    /**
     * 子图扩展查询。
     */
    @GetMapping("/subgraph")
    @Operation(summary = "子图查询", description = "以实体为种子扩展子图")
    public BaseResponse<Map<String, Object>> subgraph(@RequestParam String entityName,
                                                       @RequestParam(defaultValue = "1") int hops) {
        Long userId = getLoginUserId();
        Map<String, Object> subgraph = graphCrudService.expandSubgraph(entityName, userId, hops);
        return ResultUtils.success(subgraph);
    }

    /**
     * 图谱统计信息。
     */
    @GetMapping("/stats")
    @Operation(summary = "图谱统计", description = "获取用户图谱的节点和关系数量")
    public BaseResponse<Map<String, Long>> stats() {
        Long userId = getLoginUserId();
        Map<String, Long> stats = graphCrudService.stats(userId);
        return ResultUtils.success(stats);
    }

    /**
     * 图谱详细统计——包含实体类型分布和构建历史。
     */
    @GetMapping("/stats/detail")
    @Operation(summary = "图谱详细统计", description = "获取实体类型分布和构建历史统计")
    public BaseResponse<Map<String, Object>> statsDetail() {
        Long userId = getLoginUserId();

        // 基础统计
        Map<String, Long> basicStats = graphCrudService.stats(userId);

        // 类型分布（从 CypherTemplateEngine 查询）
        List<Map<String, Object>> typeDistribution = cypherTemplateEngine.typeStats(userId);

        // 构建历史（最近 10 条已完成任务）
        QueryWrapper qw = new QueryWrapper();
        qw.eq("user_id", userId)
            .eq("delete_flag", 0)
            .orderBy("create_time", false)
            .limit(10);
        List<GraphTask> recentTasks = graphTaskMapper.selectListByQuery(qw);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("nodeCount", basicStats.getOrDefault("nodeCount", 0L));
        detail.put("relCount", basicStats.getOrDefault("relCount", 0L));
        detail.put("typeDistribution", typeDistribution);
        detail.put("recentTasks", recentTasks);

        return ResultUtils.success(detail);
    }

    /**
     * 删除用户图谱（三步确认在后端再次校验）。
     */
    @DeleteMapping("/graph-data")
    @Operation(summary = "删除图谱", description = "删除当前用户的所有图谱数据（需确认令牌）")
    public BaseResponse<Map<String, Object>> deleteGraph(@RequestParam String confirmToken) {
        Long userId = getLoginUserId();

        if (!"DELETE".equals(confirmToken)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "确认令牌不正确，请输入 DELETE");
        }

        int deleted = graphCrudService.deleteAllByUser(userId);

        // 同时逻辑删除相关任务
        QueryWrapper qw = new QueryWrapper();
        qw.eq("user_id", userId);
        List<GraphTask> tasks = graphTaskMapper.selectListByQuery(qw);
        for (GraphTask task : tasks) {
            task.setDeleteFlag(1);
            task.setUpdateTime(LocalDateTime.now());
            graphTaskMapper.update(task);
        }

        log.warn("🗑️ [图谱删除] userId={}, 删除数={}", userId, deleted);

        Map<String, Object> result = new HashMap<>();
        result.put("deleted", deleted);
        return ResultUtils.success(result);
    }

    private Long getLoginUserId() {
        Object userObj = request.getSession().getAttribute("USER_LOGIN_STATE");
        if (userObj instanceof User currentUser) {
            return currentUser.getId();
        }
        throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
    }
}
