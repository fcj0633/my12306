package edu.swu.fcj.my12306.biz.orderservice.remote.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知支付服务关闭支付单（订单取消/超时关单链路）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderClosePayRemoteReqDTO {

    private String orderSn;
}
