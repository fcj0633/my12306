package edu.swu.fcj.my12306.biz.orderservice.remote;

import edu.swu.fcj.my12306.biz.orderservice.common.Result;
import edu.swu.fcj.my12306.biz.orderservice.remote.dto.OrderClosePayRemoteReqDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 支付服务远程调用：关闭支付单
 */
@FeignClient(name = "my12306-pay-service")
public interface PayRemoteService {

    @PostMapping("/api/pay-service/pay/close-callback")
    Result<Boolean> closePay(@RequestBody OrderClosePayRemoteReqDTO requestParam);
}
