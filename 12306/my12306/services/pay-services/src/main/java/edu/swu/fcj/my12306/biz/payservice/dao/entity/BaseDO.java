package edu.swu.fcj.my12306.biz.payservice.dao.entity;

import lombok.Data;

import java.util.Date;

/**
 * 数据公共字段（读取依赖 del_flag=0，写入时显式赋值）
 */
@Data
public class BaseDO {

    private Date createTime;

    private Date updateTime;

    private Integer delFlag;
}
