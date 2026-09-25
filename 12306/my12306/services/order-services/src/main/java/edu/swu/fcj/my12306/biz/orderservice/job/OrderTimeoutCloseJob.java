package edu.swu.fcj.my12306.biz.orderservice.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.orderservice.common.concurrent.ScheduledLockExecutor;
import edu.swu.fcj.my12306.biz.orderservice.common.constant.OrderRedisKeyConstant;
import edu.swu.fcj.my12306.biz.orderservice.common.enums.OrderStatusEnum;
import edu.swu.fcj.my12306.biz.orderservice.dao.entity.OrderDO;
import edu.swu.fcj.my12306.biz.orderservice.dao.mapper.OrderMapper;
import edu.swu.fcj.my12306.biz.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 超时关单兜底扫表
 * <p>
 * 延迟队列负责"准时"，这个任务负责"一定"。因为缓存/队列都可能因为重启、网络、误删而丢消息，
 * 只靠延迟队列会留下"永远待支付"的僵尸订单，把座位永久锁死。
 * <p>
 * 扫表范围故意收得很窄：只查"待支付 且 下单时间早于超时线"的订单，扫到的都用同一套幂等关单逻辑处理。
 * 单笔失败只记日志不中断整轮扫描；下一轮会自然重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.order.fallback-scan-enabled", havingValue = "true", matchIfMissing = true)
public class OrderTimeoutCloseJob {

    private final OrderMapper orderMapper;

    private final OrderService orderService;

    private final ScheduledLockExecutor scheduledLockExecutor;

    @Value("${my12306.order.pay-timeout-minutes:20}")
    private long payTimeoutMinutes;

    /** 同时用于调度间隔与锁租约：租约覆盖整轮，避免同一轮被两个实例各扫一遍。 */
    @Value("${my12306.order.fallback-scan-interval-ms:60000}")
    private long fallbackScanIntervalMs;

    @Scheduled(fixedDelayString = "${my12306.order.fallback-scan-interval-ms:60000}")
    public void scanTimeoutOrder() {
        // P2-4：@Scheduled 是进程内的，多实例下每个 JVM 都会各扫一遍同一批超时订单。
        // 靠 Redisson 锁收敛成"每轮只有一个实例执行"；单笔的幂等仍由条件更新保证，两者是两件事。
        scheduledLockExecutor.runWithLock(OrderRedisKeyConstant.LOCK_JOB_ORDER_TIMEOUT_SCAN,
                fallbackScanIntervalMs, this::doScanTimeoutOrder);
    }

    void doScanTimeoutOrder() {
        Date deadline = new Date(System.currentTimeMillis() - payTimeoutMinutes * 60 * 1000L);
        List<OrderDO> timeoutOrders = orderMapper.selectList(Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getStatus, OrderStatusEnum.PENDING_PAYMENT.getStatus())
                .lt(OrderDO::getOrderTime, deadline));
        if (timeoutOrders.isEmpty()) {
            return;
        }
        log.info("兜底扫表发现 {} 笔超时未支付订单", timeoutOrders.size());
        for (OrderDO each : timeoutOrders) {
            try {
                orderService.closeTimeoutOrder(each.getOrderSn());
            } catch (Throwable ex) {
                log.error("兜底扫表关单失败，orderSn={}，等待下一轮重试", each.getOrderSn(), ex);
            }
        }
    }
}
