package com.kanade.backend.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 图谱构建任务表 实体类。
 * 记录每次图谱构建任务的状态和进度，使用 MyBatis-Flex 管理。
 *
 * @author kanade
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("graph_task")
public class GraphTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务 ID
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 所属用户 ID
     */
    private Long userId;

    /**
     * 任务状态: PENDING(待分批) / PROCESSING(处理中) / COMPLETED(已完成) / FAILED(失败)
     */
    private String status;

    /**
     * 关联的文档 ID 列表（JSON 数组字符串）
     */
    private String documentIds;

    /**
     * 进度百分比 0~100
     */
    private Integer progress;

    /**
     * 批次总数
     */
    private Integer totalBatches;

    /**
     * 已完成批次数
     */
    private Integer completedBatches;

    /**
     * 错误信息（失败时记录）
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除 0未删 1已删
     */
    private Integer deleteFlag;
}
