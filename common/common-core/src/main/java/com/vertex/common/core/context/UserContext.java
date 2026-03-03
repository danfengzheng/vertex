package com.vertex.common.core.context;

/**
 * 用户上下文：通过 ThreadLocal 在请求生命周期内传递当前登录用户信息
 */
public class UserContext {

    private static final ThreadLocal<UserInfo> HOLDER = new ThreadLocal<>();

    public static void set(Long userId, String username, Integer accountType) {
        HOLDER.set(new UserInfo(userId, username, accountType));
    }

    public static UserInfo get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        UserInfo info = HOLDER.get();
        return info != null ? info.userId() : null;
    }

    public static String getUsername() {
        UserInfo info = HOLDER.get();
        return info != null ? info.username() : null;
    }

    /** 是否为管理员（accountType == 0 即系统账户） */
    public static boolean isAdmin() {
        UserInfo info = HOLDER.get();
        return info != null && info.accountType() != null && info.accountType() == 0;
    }

    public static void clear() {
        HOLDER.remove();
    }

    public record UserInfo(Long userId, String username, Integer accountType) {}
}
