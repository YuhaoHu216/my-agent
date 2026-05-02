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

-- 插入测试用户
INSERT INTO user (username, password, email, phone, status) VALUES 
('admin', '$2a$10$eACCYoUxYPKHKviQphv3PudOhdZflY4wRImEeVqTXBi6f6.f6dZfe', 'admin@example.com', '13800138000', 1),
('test', '$2a$10$eACCYoUxYPKHKviQphv3PudOhdZflY4wRImEeVqTXBi6f6.f6dZfe', 'test@example.com', '13800138001', 1);