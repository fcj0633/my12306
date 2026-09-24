package edu.swu.fcj.my12306.biz.userservice.controller;

import edu.swu.fcj.my12306.biz.userservice.common.Result;
import edu.swu.fcj.my12306.biz.userservice.common.Results;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserLoginReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.UserLoginRespDTO;
import edu.swu.fcj.my12306.biz.userservice.service.UserLoginService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户登录接口：登录 / 登录态检查 / 登出
 */
@RestController
@RequiredArgsConstructor
public class UserLoginController {

    private final UserLoginService userLoginService;

    /**
     * 登录：用户名/手机号/邮箱 + 密码
     */
    @PostMapping("/api/user-service/v1/login")
    public Result<UserLoginRespDTO> login(@RequestBody @Valid UserLoginReqDTO requestParam) {
        return Results.success(userLoginService.login(requestParam));
    }

    /**
     * 登录态检查：查 Redis 中的 accessToken
     */
    @GetMapping("/api/user-service/check-login")
    public Result<UserLoginRespDTO> checkLogin(@RequestParam("accessToken") String accessToken) {
        return Results.success(userLoginService.checkLogin(accessToken));
    }

    /**
     * 登出：删除 Redis 中的 accessToken
     */
    @GetMapping("/api/user-service/logout")
    public Result<Void> logout(@RequestParam("accessToken") String accessToken) {
        userLoginService.logout(accessToken);
        return Results.success();
    }
}
