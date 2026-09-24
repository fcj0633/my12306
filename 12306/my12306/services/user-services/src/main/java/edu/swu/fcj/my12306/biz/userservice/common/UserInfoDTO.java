package edu.swu.fcj.my12306.biz.userservice.common;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserInfoDTO {
        private String userId;
        private String username;
        private String realName;
        /** 当前请求的 accessToken（完整字符串，含 Bearer 前缀），供登出/注销删除 Redis key 使用 */
        private String token;
}
