package edu.swu.fcj.my12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 车站实体
 */
@Data
@TableName("t_station")
public class StationDO extends BaseDO {

    private Long id;

    private String code;

    private String name;

    private String spell;

    private String region;

    private String regionName;
}
