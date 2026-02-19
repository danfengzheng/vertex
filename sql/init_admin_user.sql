-- ============================================
-- 初始化管理员用户（首次部署或重置密码时执行）
-- 用户名: admin  密码: 1qaz@WSX
-- 密码已使用 BCrypt 加密，与系统登录校验一致
-- ============================================

INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `phone`, `email`, `gender`, `account_type`, `status`, `deleted`)
VALUES (1, 'admin', '$2a$10$aLYrdXnaanaJN6EEvBPOMuknsAnRwLY/kYxDyeHW2pIx1AqCngX16', '管理员', '13800138000', 'admin@vertex.com', 1, 0, 1, 0)
ON DUPLICATE KEY UPDATE `password` = VALUES(`password`), `update_time` = CURRENT_TIMESTAMP;
