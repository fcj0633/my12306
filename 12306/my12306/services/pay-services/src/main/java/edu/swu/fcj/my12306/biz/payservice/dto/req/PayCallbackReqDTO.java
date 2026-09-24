package edu.swu.fcj.my12306.biz.payservice.dto.req;

import lombok.Data;

import java.util.Date;

/**
 * 模拟第三方支付结果通知
 * <p>
 * 真实渠道会带签名，本期不做验签【待确认】。
 */
@Data
public class PayCallbackReqDTO {

    private String paySn;

    /**
     * 渠道交易凭证号
     */
    private String tradeNo;

    /**
     * 实付金额（分）：必须与支付单应付金额一致
     */
    private Integer payAmount;

    private String channel;

    private Date gmtPayment;
}
