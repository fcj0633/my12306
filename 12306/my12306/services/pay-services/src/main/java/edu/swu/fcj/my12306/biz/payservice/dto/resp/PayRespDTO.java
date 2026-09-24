package edu.swu.fcj.my12306.biz.payservice.dto.resp;

import lombok.Builder;
import lombok.Data;

/**
 * 创建支付单的返回：告诉前端去哪儿付钱
 */
@Data
@Builder
public class PayRespDTO {

    private String paySn;

    private String orderSn;

    /**
     * 应付金额（分）
     */
    private Integer totalAmount;

    /**
     * 收银台地址（模拟渠道下指向本服务的模拟收银台）
     */
    private String payUrl;

    private Integer status;
}
