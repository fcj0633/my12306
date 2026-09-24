package edu.swu.fcj.my12306.biz.orderservice.mq;

import edu.swu.fcj.my12306.biz.orderservice.common.constant.OrderRedisKeyConstant;
import edu.swu.fcj.my12306.biz.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 延迟关单消息的消费者
 * <p>
 * 设计上刻意把"触发"与"业务"分开：
 * <ul>
 *   <li>本类只负责"什么时候处理"—— 从队列取出订单号；</li>
 *   <li>真正的关单逻辑在 {@code OrderServiceImpl#closeTimeoutOrder}，业务方法可被测试直接调用，
 *       因此即使不启动 Redis 也能把逻辑测完。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.order.delay-close-enabled", havingValue = "true", matchIfMissing = true)
public class OrderDelayCloseConsumer implements InitializingBean, DisposableBean {

    /**
     * 下游通知失败时的最大重试次数，超过只记错误日志（本期不做本地消息表）
     */
    private static final int MAX_RETRY = 3;

    private final RedissonClient redissonClient;

    private final OrderService orderService;

    private volatile boolean running = true;

    private Thread worker;

    @Override
    public void afterPropertiesSet() {
        RBlockingQueue<String> blockingQueue;
        try {
            blockingQueue = redissonClient.getBlockingQueue(OrderRedisKeyConstant.DELAY_CLOSE_ORDER_QUEUE);
        } catch (Throwable ex) {
            log.error("获取延迟关单队列失败，消费线程未启动（兜底扫表仍然生效）", ex);
            return;
        }
        if (blockingQueue == null) {
            log.warn("延迟关单队列不可用，消费线程未启动（兜底扫表仍然生效）");
            return;
        }
        worker = new Thread(() -> consume(blockingQueue), "order-delay-close-consumer");
        worker.setDaemon(true);
        worker.start();
    }

    private void consume(RBlockingQueue<String> blockingQueue) {
        int retry = 0;
        while (running) {
            String orderSn = null;
            try {
                orderSn = blockingQueue.take();
                orderService.closeTimeoutOrder(orderSn);
                retry = 0;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("延迟关单消费线程被中断，退出");
                return;
            } catch (Throwable ex) {
                retry++;
                log.error("延迟关单处理失败，orderSn={}，第 {} 次重试", orderSn, retry, ex);
                if (retry <= MAX_RETRY) {
                    // 业务方法是幂等的，重投不会造成重复关单/重复回滚
                    reOffer(orderSn);
                } else {
                    log.error("延迟关单连续失败已超过 {} 次，需人工介入，orderSn={}", MAX_RETRY, orderSn);
                    retry = 0;
                }
            }
        }
    }

    private void reOffer(String orderSn) {
        if (orderSn == null) {
            return;
        }
        try {
            RBlockingQueue<String> blockingQueue =
                    redissonClient.getBlockingQueue(OrderRedisKeyConstant.DELAY_CLOSE_ORDER_QUEUE);
            if (blockingQueue == null) {
                return;
            }
            RDelayedQueue<String> delayedQueue = redissonClient.getDelayedQueue(blockingQueue);
            delayedQueue.offer(orderSn, 10, TimeUnit.SECONDS);
        } catch (Throwable ex) {
            log.error("延迟关单任务重投失败，orderSn={}", orderSn, ex);
        }
    }

    @Override
    public void destroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }
}
