package edu.swu.fcj.my12306.biz.payservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 支付渠道
 * <p>
 * 本期只实现模拟渠道：不连接任何外部系统，收银台地址指向本服务的模拟收银台，
 * 由它触发一次"渠道回调"，从而把整条支付链路在本地跑通。
 */
@Getter
@RequiredArgsConstructor
public enum PayChannelEnum {

    /**
     * 模拟支付渠道
     */
    MOCK_PAY("MOCK_PAY", "模拟支付");

    private final String name;

    private final String value;

    public static PayChannelEnum findByName(String name) {
        for (PayChannelEnum each : values()) {
            if (each.name.equals(name)) {
                return each;
            }
        }
        return null;
    }
}
