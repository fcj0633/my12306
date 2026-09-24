package edu.swu.fcj.my12306.biz.orderservice.mq;

import edu.swu.fcj.my12306.biz.orderservice.common.constant.OrderRedisKeyConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

/**
 * 延迟关单消息的生产者
 * <p>
 * 为什么用延迟队列：下单后需要在"20 分钟后"检查一次订单是否已支付。
 * 用延迟队列可以"到点再叫我"，不必每分钟全表扫描；但延迟消息本身也可能丢，
 * 所以另有 {@code OrderTimeoutCloseJob} 做兜底扫表 —— 一个负责准时，一个负责最终一定关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.order.delay-close-enabled", havingValue = "true", matchIfMissing = true)
public class OrderDelayCloseProducer {

    private final RedissonClient redissonClient;

    @Value("${my12306.order.pay-timeout-minutes:20}")
    private long payTimeoutMinutes;

    /**
     * 投递一笔"稍后检查该订单"的延迟任务
     * <p>
     * 注意：如果当前处于事务中，投递会推迟到事务提交之后执行。
     * 否则事务一旦回滚，就会留下一个"关一笔根本不存在的订单"的脏任务。
     */
    public void send(String orderSn) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(orderSn);
                }
            });
            return;
        }
        doSend(orderSn);
    }

    private void doSend(String orderSn) {
        try {
            RDelayedQueue<String> delayedQueue = getDelayedQueue();
            if (delayedQueue == null) {
                return;
            }
            delayedQueue.offer(orderSn, payTimeoutMinutes, TimeUnit.MINUTES);
            log.info("已投递延迟关单任务，orderSn={}，延迟={} 分钟", orderSn, payTimeoutMinutes);
        } catch (Throwable ex) {
            // 投递失败不能影响下单：兜底扫表会覆盖这种情况
            log.error("投递延迟关单任务失败，将由兜底扫表补上，orderSn={}", orderSn, ex);
        }
    }

    private RDelayedQueue<String> getDelayedQueue() {
        RBlockingQueue<String> blockingQueue =
                redissonClient.getBlockingQueue(OrderRedisKeyConstant.DELAY_CLOSE_ORDER_QUEUE);
        if (blockingQueue == null) {
            return null;
        }
        return redissonClient.getDelayedQueue(blockingQueue);
    }
}
