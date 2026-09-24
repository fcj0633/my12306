package edu.swu.fcj.my12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 车次可售区间实体
 */
@Data
@TableName("t_train_station_relation")
public class TrainStationRelationDO extends BaseDO {

    private Long id;

    private Long trainId;

    private String departure;

    private String arrival;

    private String startRegion;

    private String endRegion;

    private Boolean departureFlag;

    private Boolean arrivalFlag;

    private Date departureTime;

    private Date arrivalTime;
}
