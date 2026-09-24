package edu.swu.fcj.my12306.biz.ticketservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 车票状态
 * <p>
 * 取值必须与参考项目一致：P1 只产生"未支付"，P2 引入"已支付 / 已取消"两个终态。
 */
@Getter
@RequiredArgsConstructor
public enum TicketStatusEnum {

    /**
     * 未支付：座位已锁定，订单等待付款
     */
    UNPAID(0),

    /**
     * 已支付：支付成功，座位由"已锁定"推进为"已出售"
     */
    PAID(10),

    /**
     * 已取消：订单超时或被用户取消，座位已放回可售
     */
    CANCELED(30);

    private final Integer code;
}
