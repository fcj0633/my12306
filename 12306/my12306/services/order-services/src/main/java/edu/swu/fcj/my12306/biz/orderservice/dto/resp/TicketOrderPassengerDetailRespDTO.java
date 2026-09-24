package edu.swu.fcj.my12306.biz.orderservice.dto.resp;

import lombok.Data;

/**
 * 订单内乘车人明细
 */
@Data
public class TicketOrderPassengerDetailRespDTO {

    private Long id;

    private String realName;

    private Integer idType;

    private String idCard;

    private String phone;

    private Integer seatType;

    private String carriageNumber;

    private String seatNumber;

    private Integer ticketType;

    private Integer amount;

    private Integer status;
}
