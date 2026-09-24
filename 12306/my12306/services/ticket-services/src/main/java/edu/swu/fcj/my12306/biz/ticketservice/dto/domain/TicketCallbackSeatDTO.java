package edu.swu.fcj.my12306.biz.ticketservice.dto.domain;

import lombok.Data;

/**
 * 支付/取消回调中的座位坐标
 * <p>
 * 车次 + 发售区间 + 席别 + 车厢 + 座号，五项合起来才能唯一定位一行座位，
 * 也才能定位到对应的那条车票记录。
 */
@Data
public class TicketCallbackSeatDTO {

    private String carriageNumber;

    private String seatNumber;

    private Integer seatType;
}
