package edu.swu.fcj.my12306.biz.payservice.remote.dto;

import lombok.Data;

/**
 * 订单内乘车人明细（支付只需要金额与座位坐标）
 */
@Data
public class TicketOrderPassengerDetailRespDTO {

    private String realName;

    private Integer seatType;

    private String carriageNumber;

    private String seatNumber;

    /**
     * 该乘车人的票价（分）
     */
    private Integer amount;
}
