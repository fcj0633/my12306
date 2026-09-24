package edu.swu.fcj.my12306.biz.orderservice.remote.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 需要回滚的座位坐标
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelSeatRemoteDTO {

    private String carriageNumber;

    private String seatNumber;

    private Integer seatType;
}
