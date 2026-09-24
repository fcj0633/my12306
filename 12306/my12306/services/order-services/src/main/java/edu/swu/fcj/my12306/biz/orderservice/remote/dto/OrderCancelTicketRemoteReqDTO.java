package edu.swu.fcj.my12306.biz.orderservice.remote.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 通知票务服务回滚座位（座位 已锁定 -> 可售、车票 未支付 -> 已取消）
 */
@Data
@Builder
public class OrderCancelTicketRemoteReqDTO {

    private String orderSn;

    private Long trainId;

    private String departure;

    private String arrival;

    private List<OrderCancelSeatRemoteDTO> seats;
}
