package edu.swu.fcj.my12306.biz.orderservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 订单实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order")
public class OrderDO extends BaseDO {

    private Long id;

    private String orderSn;

    private Long userId;

    private String username;

    private Long trainId;

    private String trainNumber;

    private Date ridingDate;

    private String departure;

    private String arrival;

    private Date departureTime;

    private Date arrivalTime;

    private Integer source;

    private Integer status;

    private Date orderTime;

    private Integer payType;

    private Date payTime;
}
