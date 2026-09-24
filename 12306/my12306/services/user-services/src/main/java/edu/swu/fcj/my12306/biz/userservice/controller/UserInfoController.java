package edu.swu.fcj.my12306.biz.userservice.controller;

import edu.swu.fcj.my12306.biz.userservice.common.Result;
import edu.swu.fcj.my12306.biz.userservice.common.Results;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserDeletionReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserRegisterReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.UserRegisterRespDTO;
import edu.swu.fcj.my12306.biz.userservice.service.UserLoginService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户信息接口：注册 + 用户名可用性查询
 */
@RestController
@RequiredArgsConstructor
public class UserInfoController {

    private final UserLoginService userLoginService;

    /**
     * 注册：参数经 @Valid 校验（非空/格式）通过后进入 Service
     */
    @PostMapping("/api/user-service/register")
    public Result<UserRegisterRespDTO> register(@RequestBody @Valid UserRegisterReqDTO requestParam) {
        return Results.success(userLoginService.register(requestParam));
    }

    /**
     * 用户名可用性查询：注册页失焦实时校验用，true = 可用
     */
    @GetMapping("/api/user-service/has-username")
    public Result<Boolean> hasUsername(@RequestParam("username") String username) {
        return Results.success(userLoginService.hasUsername(username));
    }

    /**
     * 注销账号：当前登录用户由 Authorization 头 → UserTransmitFilter → UserContext 提供
     */
    @PostMapping("/api/user-service/deletion")
    public Result<Void> deletion(@RequestBody @Valid UserDeletionReqDTO requestParam) {
        userLoginService.deletion(requestParam);
        return Results.success();
    }
}
