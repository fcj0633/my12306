package edu.swu.fcj.my12306.biz.payservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 支付单实体：一次付款行为的记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_pay")
public class PayDO extends BaseDO {

    private Long id;

    private String paySn;

    private String orderSn;

    private Long userId;

    private String username;

    private String channel;

    private String tradeType;

    private String subject;

    /**
     * 应付金额（分）
     */
    private Integer totalAmount;

    /**
     * 实付金额（分）
     */
    private Integer payAmount;

    private String tradeNo;

    private Date gmtPayment;

    /**
     * 0 待支付 10 支付成功 30 交易关闭
     */
    private Integer status;

    /**
     * 0 下游通知未完成 1 下游通知已完成
     */
    private Integer notifyStatus;
}
