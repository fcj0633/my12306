package edu.swu.fcj.my12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 车次区间票价实体（price 单位：分）
 */
@Data
@TableName("t_train_station_price")
public class TrainStationPriceDO extends BaseDO {

    private Long id;

    private Long trainId;

    private String departure;

    private String arrival;

    private Integer seatType;

    private Integer price;
}
