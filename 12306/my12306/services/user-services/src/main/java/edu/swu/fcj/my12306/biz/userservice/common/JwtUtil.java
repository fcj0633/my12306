package edu.swu.fcj.my12306.biz.userservice.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：生成/解析访问令牌（HS512 + Bearer 前缀，思路与原项目一致）
 */
@Slf4j
public class JwtUtil {
    /** JWT 自身过期时间：24 小时（Redis 缓存是另一层 30 分钟，二者独立） */
    private static final long EXPIRATION = 86400L;
    public static final String TOKEN_PREFIX = "Bearer ";
    private static final String ISS = "my12306";
    /** 生产环境应收纳到配置中心/环境变量，不要硬编码 */
    private static final String SECRET = "my12306SecretKey039245678901232039487623456783092349288901402967890140939827";
    /** HS512 要求密钥 >= 64 字节，构造一次复用 */
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    /**
     * 生成 accessToken，载荷只放非敏感信息（userId/username/realName）
     */
    public static String generateAccessToken(UserInfoDTO userInfo) {
        return TOKEN_PREFIX + Jwts.builder()
                .signWith(SECRET_KEY, SignatureAlgorithm.HS512)
                .setIssuedAt(new Date())
                .setIssuer(ISS)
                .claim("userId", userInfo.getUserId())
                .claim("username", userInfo.getUsername())
                .claim("realName", userInfo.getRealName())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION * 1000))
                .compact();
    }

    /**
     * 解析 accessToken，过期或解析失败返回 null
     */
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
            // 解析时 jjwt 已校验签名与过期时间，这里再显式判断一次更稳妥
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
}
