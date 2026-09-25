package edu.swu.fcj.my12306.biz.orderservice.common.constant;

/**
 * 订单服务 Redis Key 常量（my12306 命名空间）
 */
public final class OrderRedisKeyConstant {

    /**
     * 延迟关单队列名：下单成功后投入，超时后自动可见，供消费线程取出关单
     */
    public static final String DELAY_CLOSE_ORDER_QUEUE = "my12306-order-service:delay-close-order-queue";

    /**
     * P2-4 定时任务锁：超时关单兜底扫表。无占位符 —— 全集群同一把锁，
     * 保证同一轮扫描只有一个实例执行（否则每个实例都会对同一批订单重复发起关单 Feign 调用）。
     */
    public static final String LOCK_JOB_ORDER_TIMEOUT_SCAN = "my12306-order-service:lock:job:order-timeout-scan";

    private OrderRedisKeyConstant() {
    }
}
