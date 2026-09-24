package edu.swu.fcj.my12306.biz.orderservice.common;

/**
 * P1 支付通知用到的 MQ 主题与标签。
 * <p>
 * ⚠️ 项目没有共享模块，这套常量在 pay / order / ticket 三个服务里各有一份，值必须一致。
 * 这是沿用项目既有约定的取舍（连 Result 和 DTO 都是各服务各一份）。
 */
public final class PayNotifyMqConstants {

    public static final String TOPIC = "my12306_pay_result";

    /** 支付成功：由 pay-services 生产，本服务消费 */
    public static final String TAG_PAY_SUCCESS = "PAY_SUCCESS";

    /** 订单已支付：由本服务生产，ticket-services 消费 */
    public static final String TAG_ORDER_PAID = "ORDER_PAID";

    private PayNotifyMqConstants() {
    }
}
