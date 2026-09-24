package edu.swu.fcj.my12306.biz.payservice.remote.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 座位坐标：票务服务靠它精确定位座位行
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketSeatRemoteDTO {

    private String carriageNumber;

    private String seatNumber;

    private Integer seatType;
}
