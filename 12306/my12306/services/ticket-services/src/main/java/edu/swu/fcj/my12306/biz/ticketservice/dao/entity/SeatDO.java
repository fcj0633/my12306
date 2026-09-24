package edu.swu.fcj.my12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 座位实体：座位绑定一个发售区间，seat_status=0 时可售
 */
@Data
@TableName("t_seat")
public class SeatDO extends BaseDO {

    private Long id;

    private Long trainId;

    private String carriageNumber;

    private String seatNumber;

    private Integer seatType;

    private String startStation;

    private String endStation;

    private Integer price;

    /**
     * 0 可售 1 已锁定 2 已出售
     */
    private Integer seatStatus;
}
