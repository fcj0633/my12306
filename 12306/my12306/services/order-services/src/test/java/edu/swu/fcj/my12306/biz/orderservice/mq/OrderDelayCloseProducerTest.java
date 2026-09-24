package edu.swu.fcj.my12306.biz.orderservice.mq;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 延迟关单投递器单元测试：不启动 Spring、不连接 Redis
 * <p>
 * 只验证一件事：下单之后确实往延迟队列投了一笔"20 分钟后叫我"的任务。
 * 这样即使测试环境没有 Redis，也能守住"超时关单主通道没有被漏掉"这条契约。
 */
class OrderDelayCloseProducerTest {

    @Test
    void send_offersDelayedTaskWithConfiguredTimeout() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBlockingQueue<String> blockingQueue = mock(RBlockingQueue.class);
        RDelayedQueue<String> delayedQueue = mock(RDelayedQueue.class);
        // getBlockingQueue/getDelayedQueue 是泛型方法，用 doReturn 避免 Mockito 的类型推断落到 Object
        doReturn(blockingQueue).when(redissonClient).getBlockingQueue(anyString());
        doReturn(delayedQueue).when(redissonClient).getDelayedQueue(blockingQueue);

        OrderDelayCloseProducer producer = new OrderDelayCloseProducer(redissonClient);
        ReflectionTestUtils.setField(producer, "payTimeoutMinutes", 20L);

        producer.send("ORDER-2026");

        verify(delayedQueue).offer("ORDER-2026", 20L, TimeUnit.MINUTES);
    }
}
