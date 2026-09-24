package edu.swu.fcj.my12306.biz.payservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 支付单状态
 */
@Getter
@RequiredArgsConstructor
public enum PayStatusEnum {

    /**
     * 待支付：支付单已创建，等待用户到渠道付款
     */
    WAIT_PAY(0),

    /**
     * 支付成功：渠道确认款项到账
     */
    PAID(10),

    /**
     * 交易关闭：订单被取消（主动或超时），支付单作废
     */
    CLOSED(30);

    private final Integer code;
}
