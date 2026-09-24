package edu.swu.fcj.my12306.biz.orderservice.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * PAY_SUCCESS 事件的消息体（消费端副本）。
 * <p>
 * 与 pay-services 里那份同形。项目无共享模块，所以两边各定义一份 ——
 * 字段一旦改动，两边都要改，这是这套约定的已知代价。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaySuccessMessage implements Serializable {

    private String orderSn;

    private String paySn;

    /**
     * 支付渠道，本服务会当作 payType 落进 t_order
     */
    private String payType;

    private Date payTime;
}
