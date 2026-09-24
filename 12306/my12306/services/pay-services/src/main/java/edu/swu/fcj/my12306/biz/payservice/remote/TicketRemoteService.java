package edu.swu.fcj.my12306.biz.payservice.remote;

import edu.swu.fcj.my12306.biz.payservice.common.Result;
import edu.swu.fcj.my12306.biz.payservice.remote.dto.TicketPayCallbackRemoteReqDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 票务服务远程调用：支付成功后把座位推进为已售
 */
@FeignClient(name = "my12306-ticket-service")
public interface TicketRemoteService {

    @PostMapping("/api/ticket-service/ticket/pay-callback")
    Result<Boolean> payCallback(@RequestBody TicketPayCallbackRemoteReqDTO requestParam);
}
