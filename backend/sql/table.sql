-- 创建数据库
CREATE DATABASE IF NOT EXISTS mokaDoc DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mokaDoc;

-- 1. 用户表
CREATE TABLE `sys_user` (
                            `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
                            `username` varchar(50) NOT NULL UNIQUE COMMENT '用户名',
                            `email` varchar(100) NOT NULL UNIQUE COMMENT '邮箱',
                            `password` varchar(100) NOT NULL COMMENT '加密密码',
                            `nickname` varchar(50) DEFAULT '' COMMENT '昵称',
                            `avatar` varchar(255) DEFAULT '' COMMENT '头像URL',
                            `status` tinyint DEFAULT 1 COMMENT '状态 1正常 0禁用',
                            `register_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
                            `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                            `delete_flag` tinyint DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
                            PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 文档表
CREATE TABLE `sys_document` (
                                `id` bigint NOT NULL AUTO_INCREMENT COMMENT '文档ID',
                                `user_id` bigint NOT NULL COMMENT '所属用户ID',
                                `name` varchar(255) NOT NULL COMMENT '文档名称',
                                `file_type` varchar(20) NOT NULL COMMENT '文件类型',
                                `file_size` bigint NOT NULL COMMENT '文件大小(字节)',
                                `file_path` varchar(512) NOT NULL COMMENT '文件存储路径',
                                `file_md5` varchar(32) UNIQUE COMMENT '文件MD5',
                                `description` varchar(512) DEFAULT '' COMMENT '文档描述',
                                `upload_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
                                `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                `delete_flag` tinyint DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
                                PRIMARY KEY (`id`),
                                KEY `idx_user_id` (`user_id`),
                                CONSTRAINT `fk_doc_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档表';

-- 3. 问答会话表
CREATE TABLE `sys_qa_session` (
                                  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话ID',
                                  `user_id` bigint NOT NULL COMMENT '所属用户ID',
                                  `session_name` varchar(100) DEFAULT '' COMMENT '会话名称',
                                  `summary` varchar(255) DEFAULT '' COMMENT '会话摘要',
                                  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                  `delete_flag` tinyint DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
                                  PRIMARY KEY (`id`),
                                  KEY `idx_user_id` (`user_id`),
                                  CONSTRAINT `fk_session_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答会话表';

-- 4. 会话消息表
CREATE TABLE `sys_qa_message` (
                                  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '消息ID',
                                  `session_id` bigint NOT NULL COMMENT '所属会话ID',
                                  `message_type` tinyint NOT NULL COMMENT '消息类型 1用户 2AI',
                                  `content` text NOT NULL COMMENT '消息内容',
                                  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
                                  `delete_flag` tinyint DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
                                  PRIMARY KEY (`id`),
                                  KEY `idx_session_id` (`session_id`),
                                  CONSTRAINT `fk_msg_session` FOREIGN KEY (`session_id`) REFERENCES `sys_qa_session` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息表';

-- 5. 会话-文档关联表
CREATE TABLE `sys_session_document` (
                                        `id` bigint NOT NULL AUTO_INCREMENT COMMENT '关联ID',
                                        `session_id` bigint NOT NULL COMMENT '会话ID',
                                        `document_id` bigint NOT NULL COMMENT '文档ID',
                                        `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '关联时间',
                                        PRIMARY KEY (`id`),
                                        UNIQUE KEY `uk_session_doc` (`session_id`,`document_id`),
                                        CONSTRAINT `fk_rel_session` FOREIGN KEY (`session_id`) REFERENCES `sys_qa_session` (`id`) ON DELETE CASCADE,
                                        CONSTRAINT `fk_rel_doc` FOREIGN KEY (`document_id`) REFERENCES `sys_document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话文档关联表';

-- 6. 回答引用溯源表
CREATE TABLE `sys_qa_reference` (
                                    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '引用ID',
                                    `message_id` bigint NOT NULL COMMENT 'AI消息ID',
                                    `document_id` bigint NOT NULL COMMENT '引用文档ID',
                                    `content` text NOT NULL COMMENT '引用原文',
                                    `page_num` int DEFAULT 0 COMMENT '页码',
                                    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                    PRIMARY KEY (`id`),
                                    KEY `idx_message_id` (`message_id`),
                                    KEY `idx_document_id` (`document_id`),
                                    CONSTRAINT `fk_ref_msg` FOREIGN KEY (`message_id`) REFERENCES `sys_qa_message` (`id`) ON DELETE CASCADE,
                                    CONSTRAINT `fk_ref_doc` FOREIGN KEY (`document_id`) REFERENCES `sys_document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回答引用表';

-- 7. 知识点表
CREATE TABLE `sys_knowledge_point` (
                                       `id` bigint NOT NULL AUTO_INCREMENT COMMENT '知识点ID',
                                       `name` varchar(100) NOT NULL COMMENT '知识点名称',
                                       `type` varchar(20) NOT NULL COMMENT '节点类型',
                                       `document_id` bigint COMMENT '关联文档ID',
                                       `content` text DEFAULT '' COMMENT '知识点描述',
                                       `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                       `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                       `delete_flag` tinyint DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
                                       PRIMARY KEY (`id`),
                                       KEY `idx_document_id` (`document_id`),
                                       CONSTRAINT `fk_know_doc` FOREIGN KEY (`document_id`) REFERENCES `sys_document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点表';

-- 8. 知识点关联表
CREATE TABLE `sys_knowledge_relation` (
                                          `id` bigint NOT NULL AUTO_INCREMENT COMMENT '关系ID',
                                          `source_id` bigint NOT NULL COMMENT '源知识点ID',
                                          `target_id` bigint NOT NULL COMMENT '目标知识点ID',
                                          `relation_type` varchar(50) NOT NULL COMMENT '关系类型',
                                          `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                          PRIMARY KEY (`id`),
                                          UNIQUE KEY `uk_relation` (`source_id`,`target_id`,`relation_type`),
                                          CONSTRAINT `fk_rel_source` FOREIGN KEY (`source_id`) REFERENCES `sys_knowledge_point` (`id`) ON DELETE CASCADE,
                                          CONSTRAINT `fk_rel_target` FOREIGN KEY (`target_id`) REFERENCES `sys_knowledge_point` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点关联表';

-- 9. 操作日志表
CREATE TABLE `sys_operation_log` (
                                     `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志ID',
                                     `user_id` bigint NOT NULL COMMENT '操作用户ID',
                                     `operation_type` varchar(50) NOT NULL COMMENT '操作类型',
                                     `operation_desc` varchar(255) NOT NULL COMMENT '操作描述',
                                     `resource_type` varchar(20) DEFAULT '' COMMENT '资源类型',
                                     `resource_id` bigint DEFAULT 0 COMMENT '资源ID',
                                     `ip_address` varchar(50) DEFAULT '' COMMENT '操作IP',
                                     `operation_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
                                     PRIMARY KEY (`id`),
                                     KEY `idx_user_id` (`user_id`),
                                     CONSTRAINT `fk_log_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';