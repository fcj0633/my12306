package edu.swu.fcj.my12306.biz.orderservice.dto.req;

import lombok.Data;

/**
 * 用户主动取消订单请求
 */
@Data
public class TicketOrderCloseReqDTO {

    private String orderSn;
}
