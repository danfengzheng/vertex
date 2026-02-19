-- 用户表增加冻结与登录失败次数字段（连续密码错误 5 次冻结，需后台解冻）
ALTER TABLE `sys_user`
    ADD COLUMN `locked` TINYINT(1) DEFAULT 0 COMMENT '是否已冻结 0-否 1-是' AFTER `status`,
    ADD COLUMN `login_fail_count` INT DEFAULT 0 COMMENT '连续登录失败次数' AFTER `locked`;
