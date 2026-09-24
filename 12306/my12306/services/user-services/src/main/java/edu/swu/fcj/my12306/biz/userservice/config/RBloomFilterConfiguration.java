package edu.swu.fcj.my12306.biz.userservice.config;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册防穿透布隆过滤器
 * <p>
 * 预期容量与误判率为简化配置（容量 64、误判率 0.03，可按用户量调整）；
 * 注意：tryInit 只生效一次，容量不可随意改小，否则已插入数据判断失效。
 */
@Configuration
public class RBloomFilterConfiguration {

    public static final String USER_REGISTER_BLOOM_FILTER_NAME = "user_register_cache_penetration_bloom_filter";

    @Bean
    public RBloomFilter<String> userRegisterCachePenetrationBloomFilter(RedissonClient redissonClient) {
        RBloomFilter<String> cachePenetrationBloomFilter = redissonClient.getBloomFilter(USER_REGISTER_BLOOM_FILTER_NAME);
        cachePenetrationBloomFilter.tryInit(64L, 0.03D);
        return cachePenetrationBloomFilter;
    }
}
