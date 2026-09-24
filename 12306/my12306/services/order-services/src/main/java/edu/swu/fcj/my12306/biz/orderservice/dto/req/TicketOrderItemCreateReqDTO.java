package edu.swu.fcj.my12306.biz.orderservice.dto.req;

import lombok.Data;

/**
 * 订单明细创建请求（一名乘车人一条）
 */
@Data
public class TicketOrderItemCreateReqDTO {

    private Integer amount;

    private String carriageNumber;

    private String seatNumber;

    private String realName;

    private Integer idType;

    private String idCard;

    private String phone;

    private Integer seatType;

    private Integer ticketType;
}
