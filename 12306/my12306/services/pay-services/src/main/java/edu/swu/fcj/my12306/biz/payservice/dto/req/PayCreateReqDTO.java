package edu.swu.fcj.my12306.biz.payservice.dto.req;

import lombok.Data;

/**
 * 创建支付单请求
 * <p>
 * 注意：**不接收金额**。金额一律由服务端根据订单明细汇总得出，前端传来的金额不可信。
 */
@Data
public class PayCreateReqDTO {

    private String orderSn;

    /**
     * 支付渠道（默认 MOCK_PAY）
     */
    private String channel;

    /**
     * 支付环境/交易类型（本期原样留存，不做分支处理）
     */
    private String tradeType;
}
