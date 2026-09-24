package edu.swu.fcj.my12306.biz.orderservice.dto.resp;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 订单详情
 */
@Data
public class TicketOrderDetailRespDTO {

    private String orderSn;

    private Long userId;

    private String username;

    private Long trainId;

    private String trainNumber;

    private Date ridingDate;

    private String departure;

    private String arrival;

    private Date departureTime;

    private Date arrivalTime;

    private Integer source;

    private Integer status;

    private Date orderTime;

    private List<TicketOrderPassengerDetailRespDTO> passengerDetails;
}
