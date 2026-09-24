package edu.swu.fcj.my12306.biz.payservice.remote.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 通知票务服务：这笔订单已支付（座位 已锁定 -> 已售）
 */
@Data
@Builder
public class TicketPayCallbackRemoteReqDTO {

    private String orderSn;

    private Long trainId;

    private String departure;

    private String arrival;

    private List<TicketSeatRemoteDTO> seats;
}
