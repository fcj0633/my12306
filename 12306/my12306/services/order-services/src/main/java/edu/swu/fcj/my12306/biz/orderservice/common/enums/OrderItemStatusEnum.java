package edu.swu.fcj.my12306.biz.orderservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 订单明细状态：必须与订单主状态同进同退，
 * 否则会出现"订单已支付、明细还显示待支付"这种自相矛盾的数据。
 */
@Getter
@RequiredArgsConstructor
public enum OrderItemStatusEnum {

    /**
     * 待支付
     */
    PENDING_PAYMENT(0),

    /**
     * 已支付
     */
    ALREADY_PAID(10),

    /**
     * 已取消
     */
    CLOSED(30);

    private final Integer status;
}
