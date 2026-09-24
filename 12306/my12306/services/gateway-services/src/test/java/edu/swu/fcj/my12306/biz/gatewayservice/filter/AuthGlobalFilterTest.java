package edu.swu.fcj.my12306.biz.gatewayservice.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthGlobalFilterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveValueOperations<String, String> valueOperations;
    private GatewayFilterChain chain;
    private AuthGlobalFilter filter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        valueOperations = mock(ReactiveValueOperations.class);
        chain = mock(GatewayFilterChain.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(chain.filter(any())).thenReturn(Mono.empty());
        filter = new AuthGlobalFilter(redisTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(filter, "internalToken", "test-gateway-token");
    }

    @Test
    void publicRouteDoesNotRequireToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/ticket-service/ticket/query").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void protectedRouteWithoutTokenReturnsUnauthorized() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/ticket-service/ticket/purchase").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    void internalRouteIsRejectedEvenWithToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/order-service/order/ticket/create")
                        .header("Authorization", "Bearer token")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    void validLoginOverwritesSpoofedIdentityHeaders() {
        when(valueOperations.get("Bearer token"))
                .thenReturn(Mono.just("{\"userId\":\"42\",\"username\":\"alice\"}"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/ticket-service/ticket/purchase")
                        .header("Authorization", "Bearer token")
                        .header(GatewayHeaderConstants.USER_ID, "spoofed")
                        .header(GatewayHeaderConstants.USER_NAME, "mallory")
                        .header(GatewayHeaderConstants.GATEWAY_TOKEN, "fake")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ArgumentCaptor<org.springframework.web.server.ServerWebExchange> captor =
                ArgumentCaptor.forClass(org.springframework.web.server.ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertEquals("42", captor.getValue().getRequest().getHeaders()
                .getFirst(GatewayHeaderConstants.USER_ID));
        assertEquals("alice", captor.getValue().getRequest().getHeaders()
                .getFirst(GatewayHeaderConstants.USER_NAME));
        assertEquals("test-gateway-token", captor.getValue().getRequest().getHeaders()
                .getFirst(GatewayHeaderConstants.GATEWAY_TOKEN));
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void missingLoginReturnsUnauthorized() {
        when(valueOperations.get("Bearer missing")).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order-service/order/ticket/query")
                        .header("Authorization", "Bearer missing")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    void redisFailureReturnsServiceUnavailable() {
        when(valueOperations.get("Bearer token")).thenReturn(Mono.error(new IllegalStateException("redis down")));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order-service/order/ticket/query")
                        .header("Authorization", "Bearer token")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }
}
