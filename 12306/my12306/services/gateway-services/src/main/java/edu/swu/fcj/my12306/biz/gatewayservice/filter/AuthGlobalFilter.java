package edu.swu.fcj.my12306.biz.gatewayservice.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 网关统一鉴权入口。
 *
 * <p>所有外部请求先移除客户端伪造的身份头；内部接口不允许经由公网网关访问，
 * 白名单请求直接放行，其余请求以 Redis 登录态为准完成认证。认证成功后由网关
 * 注入用户身份和内部令牌，下游服务只信任令牌匹配的身份头；登录态存储不可用时
 * 返回 503，避免把基础设施故障误判为用户未登录。</p>
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Set<RouteKey> PUBLIC_ROUTES = Set.of(
            route(HttpMethod.POST, "/api/user-service/register"),
            route(HttpMethod.POST, "/api/user-service/v1/login"),
            route(HttpMethod.GET, "/api/user-service/has-username"),
            route(HttpMethod.GET, "/api/user-service/check-login"),
            route(HttpMethod.GET, "/api/ticket-service/ticket/query"),
            route(HttpMethod.GET, "/api/ticket-service/region-station/query"),
            route(HttpMethod.GET, "/api/ticket-service/station/all"),
            route(HttpMethod.GET, "/api/ticket-service/train-station/query"),
            route(HttpMethod.GET, "/api/pay-service/pay/mock-cashier"),
            route(HttpMethod.POST, "/api/pay-service/pay/callback")
    );

    private static final Set<RouteKey> INTERNAL_ROUTES = Set.of(
            route(HttpMethod.GET, "/api/user-service/inner/passenger/actual/query/ids"),
            route(HttpMethod.GET, "/api/order-service/inner/order/status"),
            route(HttpMethod.POST, "/api/order-service/order/ticket/create"),
            route(HttpMethod.POST, "/api/order-service/order/ticket/pay-callback"),
            route(HttpMethod.POST, "/api/ticket-service/ticket/pay-callback"),
            route(HttpMethod.POST, "/api/ticket-service/ticket/cancel-callback"),
            route(HttpMethod.POST, "/api/pay-service/pay/close-callback")
    );

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AuthGlobalFilter(ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Value("${my12306.gateway.internal-token}")
    private String internalToken;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = sanitizeIdentityHeaders(exchange.getRequest());
        RouteKey routeKey = route(request.getMethod(), request.getPath().value());

        if (INTERNAL_ROUTES.contains(routeKey)) {
            return writeError(exchange.mutate().request(request).build(), HttpStatus.FORBIDDEN,
                    "403", "内部接口禁止通过网关访问");
        }
        if (HttpMethod.OPTIONS.equals(request.getMethod())
                || request.getPath().value().startsWith("/actuator/")
                || PUBLIC_ROUTES.contains(routeKey)) {
            return chain.filter(exchange.mutate().request(request).build());
        }

        String authorization = request.getHeaders().getFirst("Authorization");
        if (!StringUtils.hasText(authorization)) {
            return writeError(exchange.mutate().request(request).build(), HttpStatus.UNAUTHORIZED,
                    "401", "未登录或登录已过期");
        }

        return redisTemplate.opsForValue().get(authorization)
                .map(cached -> new LoginLookup(cached, false))
                .defaultIfEmpty(new LoginLookup(null, false))
                .onErrorReturn(new LoginLookup(null, true))
                .flatMap(lookup -> {
                    if (lookup.storeUnavailable()) {
                        return writeError(exchange.mutate().request(request).build(),
                                HttpStatus.SERVICE_UNAVAILABLE, "503", "登录态服务暂不可用");
                    }
                    if (!StringUtils.hasText(lookup.cached())) {
                        return writeError(exchange.mutate().request(request).build(),
                                HttpStatus.UNAUTHORIZED, "401", "未登录或登录已过期");
                    }
                    return forwardAuthenticated(exchange, chain, request, lookup.cached());
                });
    }

    private Mono<Void> forwardAuthenticated(ServerWebExchange exchange, GatewayFilterChain chain,
                                             ServerHttpRequest request, String cached) {
        try {
            JsonNode loginInfo = objectMapper.readTree(cached);
            String userId = textValue(loginInfo, "userId");
            String username = textValue(loginInfo, "username");
            if (!StringUtils.hasText(userId) || !StringUtils.hasText(username)) {
                return writeError(exchange.mutate().request(request).build(), HttpStatus.UNAUTHORIZED,
                        "401", "未登录或登录已过期");
            }
            ServerHttpRequest authenticatedRequest = request.mutate().headers(headers -> {
                headers.set(GatewayHeaderConstants.USER_ID, userId);
                headers.set(GatewayHeaderConstants.USER_NAME, username);
                headers.set(GatewayHeaderConstants.GATEWAY_TOKEN, internalToken);
            }).build();
            return chain.filter(exchange.mutate().request(authenticatedRequest).build());
        } catch (Exception ex) {
            return writeError(exchange.mutate().request(request).build(), HttpStatus.UNAUTHORIZED,
                    "401", "登录态数据无效");
        }
    }

    private ServerHttpRequest sanitizeIdentityHeaders(ServerHttpRequest request) {
        return request.mutate().headers(headers -> {
            headers.remove(GatewayHeaderConstants.USER_ID);
            headers.remove(GatewayHeaderConstants.USER_NAME);
            headers.remove(GatewayHeaderConstants.GATEWAY_TOKEN);
        }).build();
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] body = objectMapper.writeValueAsBytes(new ErrorBody(code, message, null));
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception ex) {
            byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"gateway error\"}")
                    .getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        }
    }

    private static String textValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static RouteKey route(HttpMethod method, String path) {
        return new RouteKey(method, path);
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private record RouteKey(HttpMethod method, String path) {
    }

    private record ErrorBody(String code, String message, Object data) {
    }

    private record LoginLookup(String cached, boolean storeUnavailable) {
    }
}
