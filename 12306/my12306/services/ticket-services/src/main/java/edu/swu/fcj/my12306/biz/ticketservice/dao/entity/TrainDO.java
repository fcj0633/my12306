package edu.swu.fcj.my12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 车次主表实体
 */
@Data
@TableName("t_train")
public class TrainDO extends BaseDO {

    private Long id;

    private String trainNumber;

    /**
     * 0 高铁 1 动车 2 普通车
     */
    private Integer trainType;

    private String trainTag;

    private String trainBrand;

    private String startStation;

    private String endStation;

    private String startRegion;

    private String endRegion;

    private Date saleTime;

    private Integer saleStatus;

    private Date departureTime;

    private Date arrivalTime;
}
