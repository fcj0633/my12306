package edu.swu.fcj.my12306.biz.orderservice.dto.req;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 车票订单创建请求（由 ticket-service 提交购票事实）
 */
@Data
public class TicketOrderCreateReqDTO {

    private String orderSn;

    private Long userId;

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

    private List<TicketOrderItemCreateReqDTO> ticketOrderItems;
}
