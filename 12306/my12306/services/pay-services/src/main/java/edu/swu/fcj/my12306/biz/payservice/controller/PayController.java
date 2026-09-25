package edu.swu.fcj.my12306.biz.payservice.controller;

import edu.swu.fcj.my12306.biz.payservice.common.Result;
import edu.swu.fcj.my12306.biz.payservice.common.Results;
import edu.swu.fcj.my12306.biz.payservice.common.id.SnowflakeIdGenerator;
import edu.swu.fcj.my12306.biz.payservice.dto.req.PayCallbackReqDTO;
import edu.swu.fcj.my12306.biz.payservice.dto.req.PayCloseReqDTO;
import edu.swu.fcj.my12306.biz.payservice.dto.req.PayCreateReqDTO;
import edu.swu.fcj.my12306.biz.payservice.dto.resp.PayInfoRespDTO;
import edu.swu.fcj.my12306.biz.payservice.dto.resp.PayRespDTO;
import edu.swu.fcj.my12306.biz.payservice.service.PayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

/**
 * 支付接口
 */
@RestController
@RequiredArgsConstructor
public class PayController {

    private final PayService payService;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 创建（或复用）支付单，返回收银台地址
     */
    @PostMapping("/api/pay-service/pay/create")
    public Result<PayRespDTO> createPay(@RequestBody PayCreateReqDTO requestParam) {
        return Results.success(payService.createPay(requestParam));
    }

    /**
     * 按订单号查询支付单
     */
    @GetMapping("/api/pay-service/pay/query/order-sn")
    public Result<PayInfoRespDTO> getPayInfoByOrderSn(@RequestParam("orderSn") String orderSn) {
        return Results.success(payService.getPayInfoByOrderSn(orderSn));
    }

    /**
     * 按支付流水号查询支付单
     */
    @GetMapping("/api/pay-service/pay/query/pay-sn")
    public Result<PayInfoRespDTO> getPayInfoByPaySn(@RequestParam("paySn") String paySn) {
        return Results.success(payService.getPayInfoByPaySn(paySn));
    }

    /**
     * 支付结果回调（扮演"第三方渠道的异步通知"，因此不需要登录态）
     * <p>
     * 【待确认】真实渠道必须验签，本期模拟渠道不做。
     */
    @PostMapping("/api/pay-service/pay/callback")
    public Result<Boolean> payCallback(@RequestBody PayCallbackReqDTO requestParam) {
        return Results.success(payService.payCallback(requestParam));
    }

    /**
     * 关闭支付单（由订单服务在取消/超时关单时调用，幂等）
     */
    @PostMapping("/api/pay-service/pay/close-callback")
    public Result<Boolean> closePay(@RequestBody PayCloseReqDTO requestParam) {
        return Results.success(payService.closePayByOrderSn(requestParam.getOrderSn()));
    }

    /**
     * 模拟渠道的收银台：访问它就等于"在第三方完成了一次付款"。
     * <p>
     * 它做两件事：用支付单的应付金额构造一次回调，然后走与真实渠道完全相同的回调逻辑。
     * 这样"渠道"这一层虽然不真实，但链路是真实的。
     */
    @GetMapping("/api/pay-service/pay/mock-cashier")
    public Result<String> mockCashier(@RequestParam("paySn") String paySn) {
        PayInfoRespDTO payInfo = payService.getPayInfoByPaySn(paySn);
        PayCallbackReqDTO callbackReq = new PayCallbackReqDTO();
        callbackReq.setPaySn(paySn);
        callbackReq.setPayAmount(payInfo.getTotalAmount());
        callbackReq.setChannel(payInfo.getChannel());
        // P2-5：与其他业务号统一走显式 workerId 的生成器。
        callbackReq.setTradeNo("MOCK" + snowflakeIdGenerator.nextId());
        callbackReq.setGmtPayment(new Date());
        boolean notified = payService.payCallback(callbackReq);
        return Results.success("模拟支付完成，下游通知结果：" + notified);
    }
}
