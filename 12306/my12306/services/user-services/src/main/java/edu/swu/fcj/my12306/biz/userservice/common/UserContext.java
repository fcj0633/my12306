package edu.swu.fcj.my12306.biz.userservice.common;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.Optional;

/**
 * 当前登录用户上下文（TTL ThreadLocal）
 * <p>
 * 由 UserTransmitFilter 在请求入口写入、请求结束清理；
 * TTL 保证异步线程池任务也能传递用户信息。
 */
public final class UserContext {

    private static final ThreadLocal<UserInfoDTO> USER_THREAD_LOCAL = new TransmittableThreadLocal<>();

    public static void setUser(UserInfoDTO user) {
        USER_THREAD_LOCAL.set(user);
    }

    public static String getUserId() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get()).map(UserInfoDTO::getUserId).orElse(null);
    }

    public static String getUsername() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get()).map(UserInfoDTO::getUsername).orElse(null);
    }

    public static String getRealName() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get()).map(UserInfoDTO::getRealName).orElse(null);
    }

    public static String getToken() {
        return Optional.ofNullable(USER_THREAD_LOCAL.get()).map(UserInfoDTO::getToken).orElse(null);
    }

    public static void removeUser() {
        USER_THREAD_LOCAL.remove();
    }
}
