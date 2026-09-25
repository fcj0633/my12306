package edu.swu.fcj.my12306.biz.payservice.common;

/**
 * P2-4 支付服务定时任务的分布式锁 Key。
 * <p>
 * 放在此处而不是新建 common.constant 子包：支付服务本来的常量类（{@link PayNotifyMqConstants}）
 * 就直接位于 common 下，这里沿用服务自己的既有约定。
 * <p>
 * Key 内不含实例标识（端口/主机名）—— 这是刻意的：含实例标识会让两个实例各拿各的锁、
 * 等于没锁。命名沿用本项目 my12306-&lt;服务&gt;:lock: 的前缀。
 */
public final class PayJobLockKeyConstant {

    /**
     * 支付结果通知补偿（feign 模式专用；mq 模式下该方法直接 return，不会走到锁）
     */
    public static final String LOCK_JOB_PAY_NOTIFY_COMPENSATE = "my12306-pay-service:lock:job:pay-notify-compensate";

    /**
     * 本地消息表补发扫描
     */
    public static final String LOCK_JOB_PAY_NOTIFY_MESSAGE_SCAN = "my12306-pay-service:lock:job:pay-notify-message-scan";

    /**
     * 超限未发出消息的存量上报
     */
    public static final String LOCK_JOB_PAY_NOTIFY_STUCK_REPORT = "my12306-pay-service:lock:job:pay-notify-stuck-report";

    private PayJobLockKeyConstant() {
    }
}
