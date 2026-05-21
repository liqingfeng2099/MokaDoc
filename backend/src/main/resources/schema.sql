-- ============================================================
-- MokaDoc 知识图谱模块 — MySQL 管理表 DDL
-- 在 mokaDoc 库中执行
-- ============================================================

-- 图谱构建任务表
CREATE TABLE IF NOT EXISTS graph_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '任务ID',
    user_id BIGINT NOT NULL COMMENT '所属用户ID',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|PROCESSING|COMPLETED|FAILED',
    document_ids TEXT COMMENT '关联文档ID列表(JSON数组字符串)',
    progress INT DEFAULT 0 COMMENT '进度百分比 0~100',
    total_batches INT DEFAULT 0 COMMENT '批次总数',
    completed_batches INT DEFAULT 0 COMMENT '已完成批次数',
    error_message VARCHAR(500) COMMENT '错误信息(失败时记录)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    delete_flag TINYINT DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    INDEX idx_user_id (user_id),
    INDEX idx_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图谱构建任务表';
