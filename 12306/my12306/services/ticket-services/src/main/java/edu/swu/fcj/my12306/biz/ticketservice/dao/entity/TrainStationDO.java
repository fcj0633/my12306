package edu.swu.fcj.my12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 车次经停站实体
 */
@Data
@TableName("t_train_station")
public class TrainStationDO extends BaseDO {

    private Long id;

    private Long trainId;

    private Long stationId;

    private String sequence;

    private String departure;

    private String arrival;

    private String startRegion;

    private String endRegion;

    private Date arrivalTime;

    private Date departureTime;

    /**
     * 停留时间，单位分钟
     */
    private Integer stopoverTime;
}
