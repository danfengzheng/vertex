package com.vertex.service.system.script;

import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * 生成初始化管理员用户的 SQL 脚本（密码使用 BCrypt 加密，与登录校验一致）。
 * 运行本类 main 方法即可在控制台输出可执行的 INSERT 语句。
 * <p>
 * 默认密码：1qaz@WSX
 * 使用方式：在项目根目录执行
 * ./gradlew :service:user-service:run -PmainClass=com.vertex.service.system.script.InitAdminUserScript
 * 或在 IDE 中直接运行本类 main 方法，将输出复制到 SQL 文件执行。
 */
public final class InitAdminUserScript {

    private static final String DEFAULT_PASSWORD = "1qaz@WSX";
    private static final int BCRYPT_COST = 10;

    public static void main(String[] args) {
        String password = args.length > 0 ? args[0] : DEFAULT_PASSWORD;
        String hash = BCrypt.with(BCrypt.Version.VERSION_2A).hashToString(BCRYPT_COST, password.toCharArray());
        String sql = buildInsertSql(1L, "admin", hash, "管理员", "13800138000", "admin@vertex.com", 1, 1);
        System.out.println("-- 初始化管理员（密码已 BCrypt 加密，与登录校验一致）");
        System.out.println("-- 密码: " + (args.length > 0 ? "(来自参数)" : DEFAULT_PASSWORD));
        System.out.println();
        System.out.println(sql);
    }

    private static String buildInsertSql(long id, String username, String passwordHash,
                                         String nickname, String phone, String email,
                                         int gender, int status) {
        return "INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `phone`, `email`, `gender`, `account_type`, `status`, `deleted`)\n"
                + "VALUES (" + id + ", '" + escape(username) + "', '" + escape(passwordHash) + "', '"
                + escape(nickname) + "', '" + escape(phone) + "', '" + escape(email) + "', " + gender + ", 0, " + status + ", 0)\n"
                + "ON DUPLICATE KEY UPDATE `password` = VALUES(`password`), `update_time` = CURRENT_TIMESTAMP;";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "''");
    }
}
