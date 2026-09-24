package edu.swu.fcj.my12306.biz.orderservice.controller;

import edu.swu.fcj.my12306.biz.orderservice.common.Result;
import edu.swu.fcj.my12306.biz.orderservice.common.Results;
import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderCloseReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderCreateReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderPayCallbackReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.resp.TicketOrderDetailRespDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.resp.OrderStatusQueryRespDTO;
import edu.swu.fcj.my12306.biz.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车票订单接口
 * <p>
 * create、pay-callback 和状态核对是服务间接口，不依赖用户登录态；网关会阻断
 * 外部访问，可信服务通过注册中心直连。close 是用户主动取消入口，必须带登录态。
 */
@RestController
@RequiredArgsConstructor
public class TicketOrderController {

    private final OrderService orderService;

    /**
     * 车票订单创建（返回订单号）
     */
    @PostMapping("/api/order-service/order/ticket/create")
    public Result<String> createTicketOrder(@RequestBody TicketOrderCreateReqDTO requestParam) {
        return Results.success(orderService.createTicketOrder(requestParam));
    }

    /**
     * 根据订单号查询车票订单
     */
    @GetMapping("/api/order-service/order/ticket/query")
    public Result<TicketOrderDetailRespDTO> queryTicketOrderByOrderSn(@RequestParam("orderSn") String orderSn) {
        return Results.success(orderService.queryTicketOrderByOrderSn(orderSn));
    }

    /**
     * Internal reconciliation endpoint. Missing orders are returned as exists=false so callers can
     * distinguish a confirmed absence from a transport failure.
     */
    @GetMapping("/api/order-service/inner/order/status")
    public Result<OrderStatusQueryRespDTO> queryOrderStatus(@RequestParam("orderSn") String orderSn) {
        return Results.success(orderService.queryOrderStatus(orderSn));
    }

    /**
     * 用户主动取消订单（仅待支付可取消）
     */
    @PostMapping("/api/order-service/order/ticket/close")
    public Result<Void> closeTicketOrder(@RequestBody TicketOrderCloseReqDTO requestParam) {
        orderService.closeTicketOrder(requestParam);
        return Results.success();
    }

    /**
     * 支付结果回调（由支付服务调用，幂等）
     */
    @PostMapping("/api/order-service/order/ticket/pay-callback")
    public Result<Boolean> payCallbackOrder(@RequestBody TicketOrderPayCallbackReqDTO requestParam) {
        return Results.success(orderService.payCallbackOrder(requestParam));
    }
}
