package edu.swu.fcj.my12306.biz.userservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户注销请求
 */
@Data
public class UserDeletionReqDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;
}
