package edu.swu.fcj.my12306.biz.userservice.config;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册防穿透布隆过滤器
 * <p>
 * 注意：tryInit 只生效一次，容量不可随意改小，否则已插入数据判断失效。
 * <p>
 * 2026-09-25 调整：容量 64 → 1024。原值 64 是按"简化配置"给的，但实测在本机压测准备阶段就撑爆了：
 * 注册到第 101 个用户起，连续 20 个候选用户名全部被判为"用户名已存在"，
 * 即布隆饱和后误判率趋近 100%，注册链路直接不可用。
 * 该过滤器只是"防穿透"的旁路判断，真正的唯一性由 t_user 的唯一索引兜底，
 * 因此把它调大到与真实用户量匹配不改变业务语义。
 * 部署注意：若该 key 已存在旧配置，需先删除
 * {@code user_register_cache_penetration_bloom_filter} 再重启，tryInit 才会按新容量初始化。
 */
@Configuration
public class RBloomFilterConfiguration {

    public static final String USER_REGISTER_BLOOM_FILTER_NAME = "user_register_cache_penetration_bloom_filter";

    @Bean
    public RBloomFilter<String> userRegisterCachePenetrationBloomFilter(RedissonClient redissonClient) {
        RBloomFilter<String> cachePenetrationBloomFilter = redissonClient.getBloomFilter(USER_REGISTER_BLOOM_FILTER_NAME);
        cachePenetrationBloomFilter.tryInit(1024L, 0.03D);
        return cachePenetrationBloomFilter;
    }
}
