package edu.swu.fcj.my12306.biz.userservice.controller;

import edu.swu.fcj.my12306.biz.userservice.common.Result;
import edu.swu.fcj.my12306.biz.userservice.common.Results;
import edu.swu.fcj.my12306.biz.userservice.common.UserContext;
import edu.swu.fcj.my12306.biz.userservice.dto.req.PassengerRemoveReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.PassengerReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.PassengerRespDTO;
import edu.swu.fcj.my12306.biz.userservice.service.PassengerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 乘车人接口：列表（脱敏）/ 内部按 ID 集合（明文）/ 新增 / 修改 / 移除
 */
@RestController
@RequiredArgsConstructor
public class PassengerController {

    private final PassengerService passengerService;

    /**
     * 查询当前登录用户的乘车人列表（证件/手机号脱敏）
     */
    @GetMapping("/api/user-service/passenger/query")
    public Result<List<PassengerRespDTO>> listPassengerQueryByUsername() {
        return Results.success(passengerService.listPassengerQueryByUsername(UserContext.getUsername()));
    }

    /**
     * 按乘车人 ID 集合查询（内部接口，返回明文，供购票服务调用）
     */
    @GetMapping("/api/user-service/inner/passenger/actual/query/ids")
    public Result<List<PassengerActualRespDTO>> listPassengerQueryByIds(
            @RequestParam("username") String username,
            @RequestParam("ids") List<Long> ids) {
        return Results.success(passengerService.listPassengerQueryByIds(username, ids));
    }

    /**
     * 新增乘车人
     */
    @PostMapping("/api/user-service/passenger/save")
    public Result<Void> savePassenger(@RequestBody @Valid PassengerReqDTO requestParam) {
        passengerService.savePassenger(requestParam);
        return Results.success();
    }

    /**
     * 修改乘车人
     */
    @PostMapping("/api/user-service/passenger/update")
    public Result<Void> updatePassenger(@RequestBody @Valid PassengerReqDTO requestParam) {
        passengerService.updatePassenger(requestParam);
        return Results.success();
    }

    /**
     * 移除乘车人（逻辑删除）
     */
    @PostMapping("/api/user-service/passenger/remove")
    public Result<Void> removePassenger(@RequestBody @Valid PassengerRemoveReqDTO requestParam) {
        passengerService.removePassenger(requestParam);
        return Results.success();
    }
}
