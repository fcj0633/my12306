package edu.swu.fcj.my12306.biz.payservice.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * PAY_SUCCESS 事件的消息体（生产端）。
 * <p>
 * 只带"订单侧推进状态所需的最小信息"，不带座位坐标 ——
 * 票务侧需要的坐标由它自己从 t_ticket 查（见 ticket-services 的 PayResultTicketConsumer），
 * 这样消息体不依赖任何一方的视图，也避免了"订单服务拼的坐标"与"票务自己记的坐标"出现分歧。
 * <p>
 * order-services 侧有一份同形的副本（项目无共享模块）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaySuccessMessage implements Serializable {

    private String orderSn;

    private String paySn;

    /**
     * 支付渠道，对应 t_pay.channel，订单侧会转成 payType 落库
     */
    private String payType;

    private Date payTime;
}
