package edu.swu.fcj.my12306.biz.ticketservice.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 解析工具：与 user-services 使用同一密钥（仅解析，不签发）
 */
@Slf4j
public final class JwtUtil {

    public static final String TOKEN_PREFIX = "Bearer ";

    /** 与 user-services 保持一致 */
    private static final String SECRET = "my12306SecretKey039245678901232039487623456783092349288901402967890140939827";

    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    public static UserInfoDTO parseJwtToken(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(SECRET_KEY)
                    .build()
                    .parseClaimsJws(token.replace(TOKEN_PREFIX, ""))
                    .getBody();
            if (claims.getExpiration().after(new Date())) {
                return UserInfoDTO.builder()
                        .userId(claims.get("userId", String.class))
                        .username(claims.get("username", String.class))
                        .realName(claims.get("realName", String.class))
                        .build();
            }
        } catch (Exception ex) {
            log.error("JWT Token 解析失败", ex);
        }
        return null;
    }

    private JwtUtil() {
    }
}
