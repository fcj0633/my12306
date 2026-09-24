package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketCallbackReqDTO;

/**
 * 票务侧的回调能力：支付成功与订单取消
 * <p>
 * 两个动作都必须幂等 —— 跨服务通知可能重复、可能补发，
 * 重复执行既不能报错，也不能把状态改回去。
 */
public interface TicketCallbackService {

    /**
     * 支付成功：座位 已锁定(1) -> 已出售(2)，车票 未支付(0) -> 已支付(10)
     */
    Boolean payCallback(TicketCallbackReqDTO requestParam);

    /**
     * 订单取消/超时：座位 已锁定(1) -> 可售(0)，车票 未支付(0) -> 已取消(30)
     */
    Boolean cancelCallback(TicketCallbackReqDTO requestParam);
}
