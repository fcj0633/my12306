package edu.swu.fcj.my12306.biz.payservice.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.payservice.common.PayJobLockKeyConstant;
import edu.swu.fcj.my12306.biz.payservice.common.concurrent.ScheduledLockExecutor;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayNotifyMessageStatusEnum;
import edu.swu.fcj.my12306.biz.payservice.dao.entity.PayNotifyMessageDO;
import edu.swu.fcj.my12306.biz.payservice.dao.mapper.PayNotifyMessageMapper;
import edu.swu.fcj.my12306.biz.payservice.mq.PayNotifyMessageSender;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地消息表的【补偿】一侧：把还没投递成功的消息捞出来重发。
 * <p>
 * 为什么需要它 —— 这是"可靠性不在 MQ 里"的具体体现：
 * 消息可能因为 broker 短暂不可用、网络抖动、进程在提交后发送前被 kill 而没发出去。
 * 只要消息在【同一个事务里】跟着支付状态落了库，扫描任务就能把它补上。
 * 换句话说：可靠性来自"本地落状态 + 补偿"，中间件只负责传输。
 * <p>
 * 它和 feign 模式下的 PayNotifyCompensateJob 是同一角色的两个版本：
 * 那个扫的是 t_pay.notify_status，这个扫的是 t_pay_notify_message.status。
 * <p>
 * 实现体例参照 ticket-services 的 TicketOrphanRecoveryJob：
 * @ConditionalOnProperty 门禁 + @Value 属性 + @Scheduled + 逐条 try/catch + 计数器指标。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.pay.notify-mode", havingValue = "mq")
public class PayNotifyMessageScanJob {

    private final PayNotifyMessageMapper messageMapper;

    private final PayNotifyMessageSender messageSender;

    private final MeterRegistry meterRegistry;

    private final ScheduledLockExecutor scheduledLockExecutor;

    /**
     * 当前"超过重试上限仍未发出"的消息存量。
     * <p>
     * 原实现是 {@code meterRegistry.counter(...).increment(stuck)} —— 累加。这有两个问题：
     * ① 语义错：Counter 是单调递增的累计量，而这里是"当前存量"，应该用 Gauge；
     * ② 多实例下每个实例各累加一遍，告警数字直接翻倍。
     * 加锁只能解决 ②，解决不了 ①，所以两者一起改。
     */
    private final AtomicLong stuckCount = new AtomicLong();

    @Value("${my12306.pay.notify-message-scan-batch-size:100}")
    private int batchSize;

    /** 同时用于调度间隔与锁租约：两个任务共用同一个间隔，租约也就覆盖整轮。 */
    @Value("${my12306.pay.notify-message-scan-interval-ms:10000}")
    private long notifyMessageScanIntervalMs;

    /**
     * 重试上限。超过这个次数的消息不再自动重发，只计数并告警 —— 需要人工介入。
     * 这与 RocketMQ 消费端的死信是两个不同层面：这里是【生产端】没发出去，
     * 那里是【消费端】收到了但一直处理失败。
     */
    @Value("${my12306.pay.notify-message-max-retry:10}")
    private int maxRetry;

    /** Gauge 只注册一次；任务里只更新 AtomicLong 的值，不重复注册（重复注册会拿到旧的 meter）。 */
    @PostConstruct
    void registerStuckGauge() {
        meterRegistry.gauge("my12306.pay.notify.stuck", stuckCount, AtomicLong::doubleValue);
    }

    @Scheduled(initialDelayString = "${my12306.pay.notify-message-scan-interval-ms:10000}",
            fixedDelayString = "${my12306.pay.notify-message-scan-interval-ms:10000}")
    public void resendPending() {
        // P2-4：两个实例会各扫到同一批 PENDING 消息并发出去。
        // 注意本类的"先发后标"（PayNotifyMessageSender.sendPending 先 send 再 markSent）
        // 并不能防止重复投递，下游幂等能吸收但会多出重复消息 —— 加锁后这类重复发不出去。
        scheduledLockExecutor.runWithLock(PayJobLockKeyConstant.LOCK_JOB_PAY_NOTIFY_MESSAGE_SCAN,
                notifyMessageScanIntervalMs, this::doResendPending);
    }

    void doResendPending() {
        Date now = new Date();
        List<PayNotifyMessageDO> pending = messageMapper.selectList(Wrappers.lambdaQuery(PayNotifyMessageDO.class)
                .eq(PayNotifyMessageDO::getStatus, PayNotifyMessageStatusEnum.PENDING.getCode())
                .lt(PayNotifyMessageDO::getRetryCount, maxRetry)
                // 插入时就把 next_retry_time 设为当前时间，所以新消息下一轮即可被捞起；
                // 用 le 而不是 lt，避免"刚好等于当前时刻"的消息被无限推迟。
                .le(PayNotifyMessageDO::getNextRetryTime, now)
                .orderByAsc(PayNotifyMessageDO::getId)
                .last("LIMIT " + batchSize));
        if (pending.isEmpty()) {
            return;
        }
        log.info("本地消息表兜底扫到 {} 条待发送消息", pending.size());
        for (PayNotifyMessageDO message : pending) {
            try {
                messageSender.sendPending(message);
            } catch (Throwable ex) {
                // 逐条隔离：一条失败不能影响本轮其它消息
                log.error("本地消息重发异常，等待下一轮。paySn={}", message.getPaySn(), ex);
            }
        }
    }

    /**
     * 单独把"超过重试上限仍未发出去"的消息计数暴露出来，供告警。
     */
    @Scheduled(initialDelayString = "${my12306.pay.notify-message-scan-interval-ms:10000}",
            fixedDelayString = "${my12306.pay.notify-message-scan-interval-ms:10000}")
    public void reportStuck() {
        scheduledLockExecutor.runWithLock(PayJobLockKeyConstant.LOCK_JOB_PAY_NOTIFY_STUCK_REPORT,
                notifyMessageScanIntervalMs, this::doReportStuck);
    }

    void doReportStuck() {
        Long stuck = messageMapper.selectCount(Wrappers.lambdaQuery(PayNotifyMessageDO.class)
                .eq(PayNotifyMessageDO::getStatus, PayNotifyMessageStatusEnum.PENDING.getCode())
                .ge(PayNotifyMessageDO::getRetryCount, maxRetry));
        long current = stuck == null ? 0L : stuck;
        // 设值而非累加：表达的是"当前存量"。
        stuckCount.set(current);
        if (current > 0) {
            log.error("有 {} 条支付结果消息超过重试上限仍未能发出，需要人工介入", current);
        }
    }
}
