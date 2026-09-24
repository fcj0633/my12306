package edu.swu.fcj.my12306.biz.userservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户登录响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLoginRespDTO {

    private String userId;

    private String username;

    private String realName;

    private String accessToken;
}
