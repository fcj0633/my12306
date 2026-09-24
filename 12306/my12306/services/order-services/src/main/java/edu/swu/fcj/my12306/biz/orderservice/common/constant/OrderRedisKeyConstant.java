package edu.swu.fcj.my12306.biz.orderservice.common.constant;

/**
 * 订单服务 Redis Key 常量（my12306 命名空间）
 */
public final class OrderRedisKeyConstant {

    /**
     * 延迟关单队列名：下单成功后投入，超时后自动可见，供消费线程取出关单
     */
    public static final String DELAY_CLOSE_ORDER_QUEUE = "my12306-order-service:delay-close-order-queue";

    private OrderRedisKeyConstant() {
    }
}
