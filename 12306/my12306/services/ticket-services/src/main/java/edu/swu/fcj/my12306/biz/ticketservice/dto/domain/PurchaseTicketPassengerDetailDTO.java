package edu.swu.fcj.my12306.biz.ticketservice.dto.domain;

import lombok.Data;

/**
 * 购票乘车人明细（乘车人 + 席别）
 */
@Data
public class PurchaseTicketPassengerDetailDTO {

    private String passengerId;

    private Integer seatType;
}
