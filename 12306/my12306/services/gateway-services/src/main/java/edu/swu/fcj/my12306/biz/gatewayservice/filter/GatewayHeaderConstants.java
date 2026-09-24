package edu.swu.fcj.my12306.biz.gatewayservice.filter;

/**
 * 网关与下游服务之间的可信身份头。
 * 客户端传入的同名头会被网关清除，下游仅在内部令牌匹配时使用这些身份信息。
 */
public final class GatewayHeaderConstants {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_NAME = "X-User-Name";
    public static final String GATEWAY_TOKEN = "X-Gateway-Token";

    private GatewayHeaderConstants() {
    }
}
