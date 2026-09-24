package edu.swu.fcj.my12306.biz.ticketservice.common.cache;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 模块内轻量缓存助手：只封装「读缓存 → 回源 → 写缓存」与常用 hash 操作、分布式锁执行。
 * <p>
 * 设计取舍：不新造缓存框架层，保持与 user-services 一致的直连风格；
 * 回源空值不写缓存（由调用方决定 loader 是否返回 null）。
 */
@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redissonClient;

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public void put(String key, String value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /**
     * 先读缓存，未命中则回源；回源结果非空才写入缓存
     */
    public String safeGet(String key, Supplier<String> loader, long timeout, TimeUnit unit) {
        String value = get(key);
        if (StrUtil.isNotBlank(value)) {
            return value;
        }
        value = loader.get();
        if (StrUtil.isNotBlank(value)) {
            put(key, value, timeout, unit);
        }
        return value;
    }

    /**
     * 分布式锁执行：调用方在 action 内自行实现「锁内双检」
     */
    public void executeWithLock(String lockKey, Runnable action) {
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public Object hGet(String key, String field) {
        return stringRedisTemplate.opsForHash().get(key, field);
    }

    public List<Object> hMultiGet(String key, List<Object> fields) {
        return stringRedisTemplate.opsForHash().multiGet(key, fields);
    }

    public Map<Object, Object> hGetAll(String key) {
        return stringRedisTemplate.opsForHash().entries(key);
    }

    public void hPutAll(String key, Map<Object, Object> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForHash().putAll(key, map);
    }

    /**
     * 整体写入 Hash 并设置过期时间，避免需要自愈的缓存变成永久 Key。
     */
    public void hPutAllWithTtl(String key, Map<Object, Object> map, long timeout, TimeUnit unit) {
        if (map == null || map.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForHash().putAll(key, map);
        stringRedisTemplate.expire(key, timeout, unit);
    }

    public void hPut(String key, String field, String value) {
        stringRedisTemplate.opsForHash().put(key, field, value);
    }

    /**
     * 删除整个 Key
     * <p>
     * 余票缓存是一个 hash（席别 -> 余票数），座位一旦发生占用/释放/售出，
     * 整个 hash 都不再可信，所以按 Key 整体删除让下次查询回源重算，而不是逐个字段修正。
     */
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    /**
     * 判断集合中是否全部非空（hash multiGet 场景）
     */
    public static boolean allNonNull(List<Object> values) {
        return values != null && values.stream().allMatch(Objects::nonNull);
    }
}
