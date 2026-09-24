package edu.swu.fcj.my12306.biz.userservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户登录请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginReqDTO {

    @NotBlank(message = "账号不能为空")
    private String usernameOrMailOrPhone;

    @NotBlank(message = "密码不能为空")
    private String password;
}
