package edu.swu.fcj.my12306.biz.ticketservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 座位状态
 */
@Getter
@RequiredArgsConstructor
public enum SeatStatusEnum {

    /**
     * 可售
     */
    AVAILABLE(0),

    /**
     * 已锁定
     */
    LOCKED(1),

    /**
     * 已出售
     */
    SOLD(2);

    private final Integer code;
}
