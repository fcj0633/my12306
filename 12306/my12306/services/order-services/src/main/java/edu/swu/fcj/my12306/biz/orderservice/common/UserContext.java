package edu.swu.fcj.my12306.biz.orderservice.common;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 当前登录用户上下文（TTL 线程传递，请求结束由过滤器清理）
 */
public final class UserContext {

    private static final ThreadLocal<UserInfoDTO> USER_THREAD_LOCAL = new TransmittableThreadLocal<>();

    public static void setUser(UserInfoDTO user) {
        USER_THREAD_LOCAL.set(user);
    }

    public static String getUserId() {
        UserInfoDTO user = USER_THREAD_LOCAL.get();
        return user == null ? null : user.getUserId();
    }

    public static String getUsername() {
        UserInfoDTO user = USER_THREAD_LOCAL.get();
        return user == null ? null : user.getUsername();
    }

    public static void removeUser() {
        USER_THREAD_LOCAL.remove();
    }

    private UserContext() {
    }
}
