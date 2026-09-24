package edu.swu.fcj.my12306.biz.orderservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单明细实体（一名乘车人一条）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order_item")
public class OrderItemDO extends BaseDO {

    private Long id;

    private String orderSn;

    private Long userId;

    private String username;

    private Long trainId;

    private String carriageNumber;

    private Integer seatType;

    private String seatNumber;

    private String realName;

    private Integer idType;

    private String idCard;

    private Integer ticketType;

    private String phone;

    private Integer status;

    private Integer amount;
}
