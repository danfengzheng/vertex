-- ============================================
-- 系统管理相关表结构
-- ============================================

-- 1. 用户表
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
                            `id` BIGINT NOT NULL COMMENT '用户ID',
                            `username` VARCHAR(50) NOT NULL COMMENT '用户名',
                            `password` VARCHAR(255) NOT NULL COMMENT '密码',
                            `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称',
                            `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
                            `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
                            `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像',
                            `gender` TINYINT DEFAULT 0 COMMENT '性别 0-未知 1-男 2-女',
                            `account_type` TINYINT DEFAULT 0 COMMENT '账户类型 0-系统账户 1-代理账户',
                            `status` TINYINT DEFAULT 1 COMMENT '状态 0-禁用 1-正常',
                            `create_by` BIGINT DEFAULT NULL COMMENT '创建者',
                            `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            `update_by` BIGINT DEFAULT NULL COMMENT '更新者',
                            `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                            `deleted` TINYINT DEFAULT 0 COMMENT '删除标记 0-正常 1-删除',
                            PRIMARY KEY (`id`),
                            UNIQUE KEY `uk_username` (`username`),
                            KEY `idx_phone` (`phone`),
                            KEY `idx_account_type` (`account_type`),
                            KEY `idx_status` (`status`),
                            KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 2. 菜单表
DROP TABLE IF EXISTS `sys_menu`;
CREATE TABLE `sys_menu` (
                            `id` BIGINT NOT NULL COMMENT '菜单ID',
                            `parent_id` BIGINT DEFAULT 0 COMMENT '父菜单ID',
                            `name` VARCHAR(50) NOT NULL COMMENT '菜单名称',
                            `i18n_key` VARCHAR(100) DEFAULT NULL COMMENT '多语言key',
                            `path` VARCHAR(200) DEFAULT NULL COMMENT '路由路径',
                            `component` VARCHAR(200) DEFAULT NULL COMMENT '组件路径',
                            `icon` VARCHAR(50) DEFAULT NULL COMMENT '图标',
                            `type` TINYINT NOT NULL COMMENT '菜单类型 0-目录 1-菜单 2-按钮',
                            `permission` VARCHAR(100) DEFAULT NULL COMMENT '权限标识',
                            `sort` INT DEFAULT 0 COMMENT '排序',
                            `status` TINYINT DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
                            `create_by` BIGINT DEFAULT NULL COMMENT '创建者',
                            `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            `update_by` BIGINT DEFAULT NULL COMMENT '更新者',
                            `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                            `deleted` TINYINT DEFAULT 0 COMMENT '删除标记 0-正常 1-删除',
                            PRIMARY KEY (`id`),
                            KEY `idx_parent_id` (`parent_id`),
                            KEY `idx_type` (`type`),
                            KEY `idx_status` (`status`),
                            KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单表';

-- 3. 角色表
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
                            `id` BIGINT NOT NULL COMMENT '角色ID',
                            `name` VARCHAR(50) NOT NULL COMMENT '角色名称',
                            `code` VARCHAR(50) NOT NULL COMMENT '角色编码',
                            `description` VARCHAR(200) DEFAULT NULL COMMENT '描述',
                            `status` TINYINT DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
                            `create_by` BIGINT DEFAULT NULL COMMENT '创建者',
                            `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            `update_by` BIGINT DEFAULT NULL COMMENT '更新者',
                            `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                            `deleted` TINYINT DEFAULT 0 COMMENT '删除标记 0-正常 1-删除',
                            PRIMARY KEY (`id`),
                            UNIQUE KEY `uk_code` (`code`),
                            KEY `idx_status` (`status`),
                            KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

-- 4. 角色菜单关联表
DROP TABLE IF EXISTS `sys_role_menu`;
CREATE TABLE `sys_role_menu` (
                                 `id` BIGINT NOT NULL COMMENT '关联ID',
                                 `role_id` BIGINT NOT NULL COMMENT '角色ID',
                                 `menu_id` BIGINT NOT NULL COMMENT '菜单ID',
                                 `create_by` BIGINT DEFAULT NULL COMMENT '创建者',
                                 `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                 `update_by` BIGINT DEFAULT NULL COMMENT '更新者',
                                 `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                 `deleted` TINYINT DEFAULT 0 COMMENT '删除标记 0-正常 1-删除',
                                 PRIMARY KEY (`id`),
                                 UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`),
                                 KEY `idx_role_id` (`role_id`),
                                 KEY `idx_menu_id` (`menu_id`),
                                 KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色菜单关联表';

-- 5. 用户角色关联表
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
                                 `id` BIGINT NOT NULL COMMENT '关联ID',
                                 `user_id` BIGINT NOT NULL COMMENT '用户ID',
                                 `role_id` BIGINT NOT NULL COMMENT '角色ID',
                                 `create_by` BIGINT DEFAULT NULL COMMENT '创建者',
                                 `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                 `update_by` BIGINT DEFAULT NULL COMMENT '更新者',
                                 `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                 `deleted` TINYINT DEFAULT 0 COMMENT '删除标记 0-正常 1-删除',
                                 PRIMARY KEY (`id`),
                                 UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
                                 KEY `idx_user_id` (`user_id`),
                                 KEY `idx_role_id` (`role_id`),
                                 KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

-- 插入测试数据
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `phone`, `email`, `gender`, `status`)
VALUES (1, 'admin', '123456', '管理员', '13800138000', 'admin@vertex.com', 1, 1);