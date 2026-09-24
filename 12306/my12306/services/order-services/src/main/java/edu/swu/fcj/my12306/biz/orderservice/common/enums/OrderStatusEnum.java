package edu.swu.fcj.my12306.biz.orderservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 订单状态（P2 起扩展为"待支付 / 已支付 / 已取消"三态）
 */
@Getter
@RequiredArgsConstructor
public enum OrderStatusEnum {

    /**
     * 待支付：用户选好车票下单，但还未付款
     */
    PENDING_PAYMENT(0),

    /**
     * 已支付：用户完成付款
     */
    ALREADY_PAID(10),

    /**
     * 已取消：未支付状态下被用户主动取消或超时关单
     * <p>
     * 已支付之后的"部分退款/全部退款"属于 P3，本期不引入。
     */
    CLOSED(30);

    private final Integer status;
}
