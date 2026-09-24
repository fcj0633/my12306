package edu.swu.fcj.my12306.biz.ticketservice.remote.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单明细请求（一名乘车人一条）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderItemCreateRemoteReqDTO {

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
