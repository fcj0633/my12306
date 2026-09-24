package edu.swu.fcj.my12306.biz.ticketservice.remote;

import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.PassengerActualRespDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户服务远程调用：按当前用户名 + 乘车人 ID 查实名明文信息
 */
@FeignClient(name = "my12306-user-service")
public interface UserRemoteService {

    @GetMapping("/api/user-service/inner/passenger/actual/query/ids")
    Result<List<PassengerActualRespDTO>> listPassengerQueryByIds(
            @RequestParam("username") String username,
            @RequestParam("ids") List<Long> ids);
}
