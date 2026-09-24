package edu.swu.fcj.my12306.biz.payservice.common;

/**
 * P1 支付通知用到的 MQ 主题与标签。
 * <p>
 * ⚠️ 本项目没有共享模块（连 Result、DTO 都是各服务各一份），所以这套常量在
 * pay-services / order-services / ticket-services 里各有一份，值必须保持一致。
 * 这是沿用项目既有约定的取舍，不是疏漏。
 * <p>
 * 设计上只用【一个 topic + 两个 tag】，而不是两个 topic：
 * tag 过滤是 broker 端完成的，一个 topic 更省资源，也让"支付结果"这一族事件在控制台里聚在一起。
 */
public final class PayNotifyMqConstants {

    /**
     * 支付结果事件族共用的 topic
     */
    public static final String TOPIC = "my12306_pay_result";

    /**
     * 支付成功事件：pay-services 生产，order-services 消费
     */
    public static final String TAG_PAY_SUCCESS = "PAY_SUCCESS";

    /**
     * 订单已支付事件：order-services 生产，ticket-services 消费
     * <p>
     * 为什么要有这第二个事件（链式，而不是让 ticket 直接订阅 PAY_SUCCESS）：
     * 顺序需求是真实的 —— 必须【先】把订单改成已支付，票务才能把座位改成已售。
     * 否则票务先改（座位 SOLD）而订单仍是待支付时，超时关单任务可能扫到这笔订单 → 关单 → 释放座位，
     * 而用户其实已经付钱了 → 超卖。链式把"order 成功"变成"ticket 开始"的前提。
     */
    public static final String TAG_ORDER_PAID = "ORDER_PAID";

    private PayNotifyMqConstants() {
    }
}
