package edu.swu.fcj.my12306.biz.payservice.remote.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 订单服务返回的订单详情（只取支付需要的字段）
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

    private Integer status;

    private List<TicketOrderPassengerDetailRespDTO> passengerDetails;
}
