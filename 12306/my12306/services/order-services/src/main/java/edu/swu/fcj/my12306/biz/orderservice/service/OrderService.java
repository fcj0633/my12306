package edu.swu.fcj.my12306.biz.orderservice.service;

import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderCloseReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderCreateReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderPayCallbackReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.resp.TicketOrderDetailRespDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.resp.OrderStatusQueryRespDTO;

public interface OrderService {

    /**
     * 创建车票订单（待支付），返回订单号
     * <p>
     * 内部接口，由票务服务在"占座 + 写车票"成功之后调用。
     */
    String createTicketOrder(TicketOrderCreateReqDTO requestParam);

    /**
     * 根据订单号查询订单详情
     */
    TicketOrderDetailRespDTO queryTicketOrderByOrderSn(String orderSn);

    OrderStatusQueryRespDTO queryOrderStatus(String orderSn);

    /**
     * 用户主动取消订单（仅待支付可取消）
     * <p>
     * 与超时关单复用同一套幂等关单逻辑，区别只在"谁触发"和"归属校验"。
     */
    void closeTicketOrder(TicketOrderCloseReqDTO requestParam);

    /**
     * 支付结果回调：把待支付订单推进为已支付（幂等）
     * <p>
     * 内部接口，由支付服务在渠道确认收款之后调用。
     */
    boolean payCallbackOrder(TicketOrderPayCallbackReqDTO requestParam);

    /**
     * 超时关单：把超过支付时限仍未支付的订单关闭，并通知支付服务作废支付单、票务服务回滚座位
     * <p>
     * 这是"延迟队列消费者"和"兜底扫表任务"共同调用的幂等业务方法。
     * 之所以从触发器中拆出来，是为了让业务逻辑可以脱离 Redis 被直接测试和重放。
     */
    void closeTimeoutOrder(String orderSn);
}
