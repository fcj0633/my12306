package edu.swu.fcj.my12306.biz.userservice.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 用户注册请求
 */
@Data
public class UserRegisterReqDTO {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[^@]+$", message = "用户名不能包含 @")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    @NotBlank(message = "真实姓名不能为空")
    private String realName;

    @NotNull(message = "证件类型不能为空")
    private Integer idType;

    @NotBlank(message = "证件号码不能为空")
    private String idCard;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String mail;

    private Integer userType;

    private Integer verifyState;

    private String postCode;

    private String address;

    private String region;

    private String telephone;
}
