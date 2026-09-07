-- 用户表初始化脚本
CREATE DATABASE IF NOT EXISTS my_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE my_agent;

DROP TABLE IF EXISTS user;

CREATE TABLE user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码',
    email VARCHAR(100) NOT NULL UNIQUE COMMENT '邮箱',
    phone VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    status TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    INDEX idx_username (username),
    INDEX idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

DROP TABLE IF EXISTS user_document;

CREATE TABLE user_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '文档ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_path VARCHAR(500) NOT NULL COMMENT '磁盘存储路径(相对)',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    file_extension VARCHAR(10) NOT NULL COMMENT '文件扩展名',
    chunk_count INT NOT NULL DEFAULT 0 COMMENT '分片数量',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-已删除, 1-正常',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户文档表';

-- 插入测试用户
INSERT INTO user (username, password, email, phone, status) VALUES
('admin', '$2a$10$eACCYoUxYPKHKviQphv3PudOhdZflY4wRImEeVqTXBi6f6.f6dZfe', 'admin@example.com', '13800138000', 1),
('test', '$2a$10$eACCYoUxYPKHKviQphv3PudOhdZflY4wRImEeVqTXBi6f6.f6dZfe', 'test@example.com', '13800138001', 1);

-- MCP 服务配置表（用户自定义 SSE MCP 服务）
DROP TABLE IF EXISTS user_mcp_server;

CREATE TABLE user_mcp_server (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    server_name VARCHAR(100) NOT NULL COMMENT '服务名称(同一用户内唯一)',
    url VARCHAR(500) NOT NULL COMMENT 'MCP 服务 SSE 端点地址',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
    connect_status TINYINT DEFAULT 0 COMMENT '连接状态: 0-未检测, 1-连接正常, 2-连接失败',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_server_name (user_id, server_name),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户MCP服务配置表';

-- 用户自定义 LLM 配置表（每个供应商一个 api-key，key 下可配置多个模型）
DROP TABLE IF EXISTS user_llm_config;

CREATE TABLE user_llm_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    provider VARCHAR(20) NOT NULL COMMENT '提供商: DASHSCOPE-通义千问, DEEPSEEK-DeepSeek',
    api_key VARCHAR(255) NOT NULL COMMENT 'API Key(明文存储，接口返回时脱敏)',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_provider (user_id, provider),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户LLM供应商配置表';

-- 用户 LLM 模型配置表（每个供应商下多个模型名）
DROP TABLE IF EXISTS user_llm_model;

CREATE TABLE user_llm_model (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    provider VARCHAR(20) NOT NULL COMMENT '提供商: DASHSCOPE-通义千问, DEEPSEEK-DeepSeek',
    model_name VARCHAR(100) NOT NULL COMMENT '模型名称',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_provider_model (user_id, provider, model_name),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户LLM模型配置表';

-- 用户技能表（提示词知识包）
DROP TABLE IF EXISTS user_skill;

CREATE TABLE user_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    skill_name VARCHAR(100) NOT NULL COMMENT '技能名称(同一用户内唯一)',
    skill_content TEXT NOT NULL COMMENT '技能提示词内容(知识包)',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_skill_name (user_id, skill_name),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户技能表';

-- 用户自定义 Agent 表
DROP TABLE IF EXISTS user_agent;

CREATE TABLE user_agent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    agent_name VARCHAR(100) NOT NULL COMMENT 'Agent名称(同一用户内唯一)',
    system_prompt TEXT NOT NULL COMMENT 'Agent系统提示词',
    next_step_prompt TEXT DEFAULT NULL COMMENT '下一步提示词(可空，为空用内置兜底)',
    provider VARCHAR(20) NOT NULL COMMENT '模型提供商: DASHSCOPE-通义千问, DEEPSEEK-DeepSeek',
    model_name VARCHAR(100) NOT NULL COMMENT '模型名称',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_agent_name (user_id, agent_name),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户自定义Agent表';

-- Agent 与 Skill 绑定表
DROP TABLE IF EXISTS user_agent_skill;

CREATE TABLE user_agent_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    agent_id BIGINT NOT NULL COMMENT 'Agent ID',
    skill_id BIGINT NOT NULL COMMENT 'Skill ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_agent_skill (user_id, agent_id, skill_id),
    INDEX idx_user_id (user_id),
    INDEX idx_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent-Skill绑定表';

-- Agent 与 MCP Server 绑定表
DROP TABLE IF EXISTS user_agent_mcp;

CREATE TABLE user_agent_mcp (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    agent_id BIGINT NOT NULL COMMENT 'Agent ID',
    mcp_server_id BIGINT NOT NULL COMMENT 'MCP Server ID(对应user_mcp_server.id)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_agent_mcp (user_id, agent_id, mcp_server_id),
    INDEX idx_user_id (user_id),
    INDEX idx_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent-MCP绑定表';

-- 用户编排器表（主 agent）
DROP TABLE IF EXISTS user_orchestrator;

CREATE TABLE user_orchestrator (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    orchestrator_name VARCHAR(100) NOT NULL COMMENT '编排器名称(同一用户内唯一)',
    system_prompt TEXT NOT NULL COMMENT '编排器系统提示词',
    provider VARCHAR(20) NOT NULL COMMENT '模型提供商: DASHSCOPE-通义千问, DEEPSEEK-DeepSeek',
    model_name VARCHAR(100) NOT NULL COMMENT '模型名称',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_orchestrator_name (user_id, orchestrator_name),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户编排器表';

-- 编排器与子 Agent 关联表
DROP TABLE IF EXISTS user_orchestrator_agent;

CREATE TABLE user_orchestrator_agent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    orchestrator_id BIGINT NOT NULL COMMENT '编排器ID',
    agent_id BIGINT NOT NULL COMMENT '子Agent ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_orch_agent (user_id, orchestrator_id, agent_id),
    INDEX idx_user_id (user_id),
    INDEX idx_orchestrator_id (orchestrator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='编排器-子Agent关联表';