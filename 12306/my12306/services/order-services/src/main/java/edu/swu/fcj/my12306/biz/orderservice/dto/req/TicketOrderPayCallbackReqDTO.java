package edu.swu.fcj.my12306.biz.orderservice.dto.req;

import lombok.Data;

import java.util.Date;

/**
 * 支付结果回调请求（由支付服务调用）
 */
@Data
public class TicketOrderPayCallbackReqDTO {

    private String orderSn;

    private String paySn;

    /**
     * 支付渠道，订单侧记录为 pay_type
     */
    private String payType;

    private Date payTime;
}
