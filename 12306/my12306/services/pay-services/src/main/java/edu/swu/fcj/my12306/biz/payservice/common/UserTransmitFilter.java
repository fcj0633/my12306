package edu.swu.fcj.my12306.biz.payservice.common;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Objects;

/**
 * 用户信息透传过滤器。
 * 优先接收携带正确内部令牌的网关身份；直连服务时兼容 Authorization，并以 Redis
 * 登录态为事实源解析 JWT。请求结束后始终清理线程上下文，避免线程复用导致身份串号。
 */
@Component
@RequiredArgsConstructor
public class UserTransmitFilter implements Filter {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${my12306.gateway.internal-token:my12306-local-gateway}")
    private String gatewayInternalToken;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        String authorization = httpServletRequest.getHeader("Authorization");
        String gatewayToken = httpServletRequest.getHeader("X-Gateway-Token");
        String gatewayUserId = httpServletRequest.getHeader("X-User-Id");
        String gatewayUsername = httpServletRequest.getHeader("X-User-Name");
        // 身份头本身不可信，只有网关内部令牌匹配时才允许写入用户上下文。
        if (Objects.equals(gatewayInternalToken, gatewayToken)
                && StringUtils.hasText(gatewayUserId) && StringUtils.hasText(gatewayUsername)) {
            UserContext.setUser(UserInfoDTO.builder()
                    .userId(gatewayUserId)
                    .username(gatewayUsername)
                    .token(authorization)
                    .build());
        } else if (StringUtils.hasText(authorization)) {
            String cached = stringRedisTemplate.opsForValue().get(authorization);
            if (StringUtils.hasText(cached)) {
                UserInfoDTO userInfo = JwtUtil.parseJwtToken(authorization);
                if (userInfo != null) {
                    userInfo.setToken(authorization);
                    UserContext.setUser(userInfo);
                }
            }
        }
        try {
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            UserContext.removeUser();
        }
    }
}
