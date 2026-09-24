package edu.swu.fcj.my12306.biz.userservice.dto.resp;

import lombok.Data;

/**
 * 用户注册响应
 */
@Data
public class UserRegisterRespDTO {

    private String username;

    private String realName;

    private String phone;
}
