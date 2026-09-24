package edu.swu.fcj.my12306.biz.ticketservice.remote;

import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.OrderStatusRemoteRespDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 订单服务远程调用：创建待支付车票订单
 */
@FeignClient(name = "my12306-order-service")
public interface OrderRemoteService {

    @PostMapping("/api/order-service/order/ticket/create")
    Result<String> createTicketOrder(@RequestBody TicketOrderCreateRemoteReqDTO requestParam);

    @GetMapping("/api/order-service/inner/order/status")
    Result<OrderStatusRemoteRespDTO> queryOrderStatus(@RequestParam("orderSn") String orderSn);
}
