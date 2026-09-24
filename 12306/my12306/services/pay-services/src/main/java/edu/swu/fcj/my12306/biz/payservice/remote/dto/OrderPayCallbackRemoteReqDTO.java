package edu.swu.fcj.my12306.biz.payservice.remote.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 通知订单服务：这笔订单已支付
 */
@Data
@Builder
public class OrderPayCallbackRemoteReqDTO {

    private String orderSn;

    private String paySn;

    /**
     * 支付渠道（订单侧记录为 pay_type）
     */
    private String payType;

    private Date payTime;
}
