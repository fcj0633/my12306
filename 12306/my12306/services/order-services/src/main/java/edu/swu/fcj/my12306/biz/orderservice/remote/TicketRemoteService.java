package edu.swu.fcj.my12306.biz.orderservice.remote;

import edu.swu.fcj.my12306.biz.orderservice.common.Result;
import edu.swu.fcj.my12306.biz.orderservice.remote.dto.OrderCancelTicketRemoteReqDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 票务服务远程调用：取消订单后回滚座位
 */
@FeignClient(name = "my12306-ticket-service")
public interface TicketRemoteService {

    @PostMapping("/api/ticket-service/ticket/cancel-callback")
    Result<Boolean> cancelCallback(@RequestBody OrderCancelTicketRemoteReqDTO requestParam);
}
