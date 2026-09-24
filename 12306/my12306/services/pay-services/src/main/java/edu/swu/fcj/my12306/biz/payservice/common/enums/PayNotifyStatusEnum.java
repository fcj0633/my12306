package edu.swu.fcj.my12306.biz.payservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 支付结果的下游通知状态
 * <p>
 * 支付成功后需要通知订单服务和票务服务，任一步失败都不能改变"钱已收到"这个事实，
 * 只把通知状态置为未完成，由定时任务重推 —— 这是本期实现最终一致性的核心手段。
 */
@Getter
@RequiredArgsConstructor
public enum PayNotifyStatusEnum {

    /**
     * 未完成：至少有一个下游还没通知成功，需要重推
     */
    NOT_NOTIFIED(0),

    /**
     * 已完成：订单与票务都已通知成功
     */
    NOTIFIED(1);

    private final Integer code;
}
