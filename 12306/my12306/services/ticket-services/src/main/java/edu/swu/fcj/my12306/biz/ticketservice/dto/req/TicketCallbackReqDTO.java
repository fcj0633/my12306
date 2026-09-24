package edu.swu.fcj.my12306.biz.ticketservice.dto.req;

import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.TicketCallbackSeatDTO;
import lombok.Data;

import java.util.List;

/**
 * 支付成功 / 订单取消回调入参（由订单服务与支付服务调用，内部接口）
 * <p>
 * 【待确认】内部接口鉴权方案本期未实现。
 */
@Data
public class TicketCallbackReqDTO {

    private String orderSn;

    private Long trainId;

    private String departure;

    private String arrival;

    private List<TicketCallbackSeatDTO> seats;
}
