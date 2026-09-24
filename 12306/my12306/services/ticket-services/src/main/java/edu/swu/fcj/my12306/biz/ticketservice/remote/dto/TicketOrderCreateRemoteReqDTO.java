package edu.swu.fcj.my12306.biz.ticketservice.remote.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 订单服务创建订单请求（购票事实）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderCreateRemoteReqDTO {

    private String orderSn;

    private String userId;

    private String username;

    private Long trainId;

    private String departure;

    private String arrival;

    private Integer source;

    private Date orderTime;

    private Date ridingDate;

    private String trainNumber;

    private Date departureTime;

    private Date arrivalTime;

    private List<TicketOrderItemCreateRemoteReqDTO> ticketOrderItems;
}
