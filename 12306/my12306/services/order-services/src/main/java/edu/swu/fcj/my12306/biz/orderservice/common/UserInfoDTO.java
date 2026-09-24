package edu.swu.fcj.my12306.biz.orderservice.common;

import lombok.Builder;
import lombok.Data;

/**
 * 登录用户信息（与 user-services 的 JWT 载荷一致）
 */
@Data
@Builder
public class UserInfoDTO {

    private String userId;

    private String username;

    private String realName;

    private String token;
}
