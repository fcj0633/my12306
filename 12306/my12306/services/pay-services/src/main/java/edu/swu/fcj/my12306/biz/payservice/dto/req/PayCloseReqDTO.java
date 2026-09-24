package edu.swu.fcj.my12306.biz.payservice.dto.req;

import lombok.Data;

/**
 * 关闭支付单请求（订单取消/超时关单时由订单服务调用）
 */
@Data
public class PayCloseReqDTO {

    private String orderSn;
}
