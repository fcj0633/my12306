package edu.swu.fcj.my12306.biz.payservice.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.payservice.common.PayJobLockKeyConstant;
import edu.swu.fcj.my12306.biz.payservice.common.concurrent.ScheduledLockExecutor;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayNotifyStatusEnum;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayStatusEnum;
import edu.swu.fcj.my12306.biz.payservice.dao.entity.PayDO;
import edu.swu.fcj.my12306.biz.payservice.dao.mapper.PayMapper;
import edu.swu.fcj.my12306.biz.payservice.service.PayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 支付结果通知补偿任务
 * <p>
 * 只扫"支付成功 + 通知未完成"的支付单，重推给订单与票务。
 * 之所以只扫未完成：扫描范围越小越便宜，已完成的单没有任何需要补的动作。
 * <p>
 * 注意：这里不改变"支付成功"这个事实，只是把还没传播到位的结果补发出去。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.pay.notify-compensate-enabled", havingValue = "true", matchIfMissing = true)
public class PayNotifyCompensateJob {

    private final PayMapper payMapper;

    private final PayService payService;

    private final ScheduledLockExecutor scheduledLockExecutor;

    /**
     * 与 {@link #compensate()} 是同一个开关的两种用法，语义必须一致：只有 feign 模式才做补偿。
     */
    @Value("${my12306.pay.notify-mode:feign}")
    private String notifyMode;

    /** 同时用于调度间隔与锁租约：租约覆盖整轮。 */
    @Value("${my12306.pay.notify-compensate-interval-ms:60000}")
    private long notifyCompensateIntervalMs;

    @Scheduled(fixedDelayString = "${my12306.pay.notify-compensate-interval-ms:60000}")
    public void compensate() {
        // ⚠️ mq 模式下本任务必须【停用】，否则会破坏 mq 模式本身：
        //
        // 本任务的判据是 t_pay.notify_status = 0（"还没通知下游"），而 notify_status 只有
        // feign 路径的 notifyPayResult 会置为 1。mq 模式走的是本地消息表，
        // t_pay.notify_status 会【永远停在 0】—— 于是这个任务会把每一笔已支付订单都捞出来，
        // 重新走一遍同步 Feign 通知。那等于把刚拆掉的同步串行链路又接了回去，
        // 既让 mq 模式名不副实，也让 feign/mq 的 A/B 对比失去意义。
        //
        // mq 模式下"重推未完成的通知"由 PayNotifyMessageScanJob 负责（它扫的是本地消息表），
        // 两者是同一角色的两个模式版本，不该同时工作。
        if ("mq".equalsIgnoreCase(notifyMode)) {
            return;
        }
        // P2-4：feign 模式下多实例会各扫一遍同一批待补偿支付单，对同一批单重复发起同步 Feign 通知。
        // 这一段放在抢锁之前判断，是为了不在 mq 模式下刷出无意义的"跳过"日志与计数器。
        scheduledLockExecutor.runWithLock(PayJobLockKeyConstant.LOCK_JOB_PAY_NOTIFY_COMPENSATE,
                notifyCompensateIntervalMs, this::doCompensate);
    }

    void doCompensate() {
        List<PayDO> payList = payMapper.selectList(Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getStatus, PayStatusEnum.PAID.getCode())
                .eq(PayDO::getNotifyStatus, PayNotifyStatusEnum.NOT_NOTIFIED.getCode()));
        if (payList.isEmpty()) {
            return;
        }
        for (PayDO each : payList) {
            boolean success = payService.notifyPayResult(each.getPaySn());
            log.info("支付结果通知补偿，paySn={}，orderSn={}，结果={}", each.getPaySn(), each.getOrderSn(), success);
        }
    }
}
