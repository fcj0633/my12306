package edu.swu.fcj.my12306.biz.ticketservice.common.toolkit;

/**
 * 缓存 Key 拼接工具：约定各片段用下划线连接
 */
public final class CacheUtil {

    public static String buildKey(String... parts) {
        return String.join("_", parts);
    }

    private CacheUtil() {
    }
}
