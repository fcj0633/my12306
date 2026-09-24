package edu.swu.fcj.my12306.biz.payservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 订单状态（跨服务契约：取值必须与 order-services 的 OrderStatusEnum 完全一致）
 * <p>
 * 这里刻意用一份本地枚举而不是共享 jar：当前项目还没有抽取公共组件库，
 * 各服务之间靠"接口约定"保持一致，这也是后续引入 frameworks 组件的动机之一。
 */
@Getter
@RequiredArgsConstructor
public enum OrderStatusEnum {

    /**
     * 待支付
     */
    PENDING_PAYMENT(0),

    /**
     * 已支付
     */
    ALREADY_PAID(10),

    /**
     * 已取消
     */
    CLOSED(30);

    private final Integer status;
}
