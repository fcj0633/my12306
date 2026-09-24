package edu.swu.fcj.my12306.biz.ticketservice.controller;

import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.common.Results;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketCallbackReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.PurchaseTicketService;
import edu.swu.fcj.my12306.biz.ticketservice.service.TicketCallbackService;
import edu.swu.fcj.my12306.biz.ticketservice.service.TicketService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车票控制层：P0 查询 + P1 购票 + P2 支付/取消回调
 */
@RestController
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    private final PurchaseTicketService purchaseTicketService;

    private final TicketCallbackService ticketCallbackService;

    private final MeterRegistry meterRegistry;

    /**
     * 根据条件查询车票：车次列表 + 票价 + 余票
     */
    @GetMapping("/api/ticket-service/ticket/query")
    public Result<TicketPageQueryRespDTO> pageListTicketQuery(TicketPageQueryReqDTO requestParam) {
        return Results.success(ticketService.pageListTicketQuery(requestParam));
    }

    /**
     * 购买车票：返回待支付订单号与乘车人明细
     */
    @PostMapping("/api/ticket-service/ticket/purchase")
    public Result<TicketPurchaseRespDTO> purchaseTickets(@RequestBody PurchaseTicketReqDTO requestParam) {
        try {
            TicketPurchaseRespDTO result = purchaseTicketService.purchaseTickets(requestParam);
            // 代理服务正常返回意味着事务已提交，此时才计为一次成功购票。
            meterRegistry.counter("my12306.purchase.success").increment();
            return Results.success(result);
        } catch (RuntimeException ex) {
            // 业务拒绝和运行时故障统一进入失败计数，异常仍交给全局处理器响应。
            meterRegistry.counter("my12306.purchase.fail").increment();
            throw ex;
        }
    }

    /**
     * 支付成功回调：把座位推进为"已出售"、车票推进为"已支付"（内部接口，幂等）
     */
    @PostMapping("/api/ticket-service/ticket/pay-callback")
    public Result<Boolean> payCallback(@RequestBody TicketCallbackReqDTO requestParam) {
        return Results.success(ticketCallbackService.payCallback(requestParam));
    }

    /**
     * 订单取消回调：把座位放回"可售"、车票推进为"已取消"（内部接口，幂等）
     */
    @PostMapping("/api/ticket-service/ticket/cancel-callback")
    public Result<Boolean> cancelCallback(@RequestBody TicketCallbackReqDTO requestParam) {
        return Results.success(ticketCallbackService.cancelCallback(requestParam));
    }
}
