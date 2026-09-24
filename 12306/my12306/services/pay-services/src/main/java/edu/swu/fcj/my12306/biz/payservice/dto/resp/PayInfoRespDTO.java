package edu.swu.fcj.my12306.biz.payservice.dto.resp;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 支付单详情
 */
@Data
@Builder
public class PayInfoRespDTO {

    private String paySn;

    private String orderSn;

    private Long userId;

    private String username;

    private String channel;

    private String tradeType;

    private String subject;

    private Integer totalAmount;

    private Integer payAmount;

    private String tradeNo;

    private Date gmtPayment;

    private Integer status;

    private Integer notifyStatus;
}
