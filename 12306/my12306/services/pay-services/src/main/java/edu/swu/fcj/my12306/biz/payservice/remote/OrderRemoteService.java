package edu.swu.fcj.my12306.biz.payservice.remote;

import edu.swu.fcj.my12306.biz.payservice.common.Result;
import edu.swu.fcj.my12306.biz.payservice.remote.dto.OrderPayCallbackRemoteReqDTO;
import edu.swu.fcj.my12306.biz.payservice.remote.dto.TicketOrderDetailRespDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 订单服务远程调用：查询订单详情（校验归属、汇总金额）、推进订单支付状态
 */
@FeignClient(name = "my12306-order-service")
public interface OrderRemoteService {

    @GetMapping("/api/order-service/order/ticket/query")
    Result<TicketOrderDetailRespDTO> queryTicketOrderByOrderSn(@RequestParam("orderSn") String orderSn);

    @PostMapping("/api/order-service/order/ticket/pay-callback")
    Result<Boolean> payCallbackOrder(@RequestBody OrderPayCallbackRemoteReqDTO requestParam);
}
