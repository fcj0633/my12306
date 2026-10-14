package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named="SEGMENT_REDIS_TEST",matches="true")
class SegmentLuaTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    String key;
    DefaultRedisScript<Long> script;
    @BeforeEach void setup() {
        var config=new RedisStandaloneConfiguration(System.getenv().getOrDefault("SEGMENT_REDIS_HOST","127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("SEGMENT_REDIS_PORT","6379")));
        String password=System.getenv("SEGMENT_REDIS_PASSWORD");if(password!=null && !password.isBlank()) config.setPassword(password);
        factory=new LettuceConnectionFactory(config);factory.afterPropertiesSet();factory.start();
        redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();
        key="codex:segment-lua-test:"+UUID.randomUUID();
        script=new DefaultRedisScript<>();script.setLocation(new ClassPathResource("lua/take_token_from_bucket.lua"));script.setResultType(Long.class);
    }
    @AfterEach void cleanup() {if(redis!=null && key!=null) redis.delete(key);if(factory!=null) factory.destroy();}
    @Test void insufficientOrMissingFieldNeverPartiallyDeducts() {
        redis.opsForHash().putAll(key,Map.of("0_2","2","1_2","0"));
        assertEquals(0L,redis.execute(script,List.of(key),"0_2","1","1_2","1"));
        assertEquals("2",redis.opsForHash().get(key,"0_2"));
        assertEquals(-1L,redis.execute(script,List.of(key),"0_2","1","2_2","1"));
        assertEquals("2",redis.opsForHash().get(key,"0_2"));
    }
    @Test void concurrentOverlappingCapacityHasExactlyOneAdmission() throws Exception {
        redis.opsForHash().putAll(key,Map.of("0_2","1","1_2","1","2_2","1"));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(() -> {start.await();return redis.execute(script,List.of(key),"0_2","1","1_2","1");});
            var b=pool.submit(() -> {start.await();return redis.execute(script,List.of(key),"1_2","1","2_2","1");});start.countDown();
            assertEquals(1L,a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS));
        }
        assertEquals("0",redis.opsForHash().get(key,"1_2"));
    }
    @Test void completeSnapshotReplacesOldFieldsAndSetsTtlAtomically() {
        redis.opsForHash().put(key,"obsolete","100");
        var helper=new RedisCacheHelper(redis,mock(RedissonClient.class));
        helper.hPutAllWithTtl(key,Map.of("0_2","3","1_2","4"),30,TimeUnit.SECONDS);
        assertFalse(redis.opsForHash().hasKey(key,"obsolete"));assertEquals(2L,redis.opsForHash().size(key));
        assertTrue(redis.getExpire(key)>0 && redis.getExpire(key)<=30);
        redis.delete(key);assertEquals(-1L,redis.execute(script,List.of(key),"0_2","1"));assertFalse(redis.hasKey(key));
    }
}
