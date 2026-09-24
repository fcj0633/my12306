package edu.swu.fcj.my12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 地区实体
 */
@Data
@TableName("t_region")
public class RegionDO extends BaseDO {

    private Long id;

    private String name;

    private String fullName;

    private String code;

    private String initial;

    private String spell;

    private Integer popularFlag;
}
