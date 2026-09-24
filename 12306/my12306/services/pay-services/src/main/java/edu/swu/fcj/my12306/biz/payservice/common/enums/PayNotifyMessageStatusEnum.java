package edu.swu.fcj.my12306.biz.payservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 本地消息表里一条待发送消息的发送状态
 */
@Getter
@RequiredArgsConstructor
public enum PayNotifyMessageStatusEnum {

    /**
     * 待发送：已与支付状态在同一个事务里落库，但还没成功投递到 MQ
     */
    PENDING(0),

    /**
     * 已发送：MQ 已返回 SEND_OK（同步刷盘下表示消息已落盘）
     */
    SENT(10);

    private final Integer code;
}
