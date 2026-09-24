package edu.swu.fcj.my12306.biz.ticketservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 席别（座位类型）
 */
@Getter
@RequiredArgsConstructor
public enum VehicleSeatTypeEnum {

    BUSINESS_CLASS(0, "商务座"),
    FIRST_CLASS(1, "一等座"),
    SECOND_CLASS(2, "二等座"),
    SECOND_CLASS_CABIN_SEAT(3, "二等包座"),
    FIRST_SLEEPER(4, "一等卧"),
    SECOND_SLEEPER(5, "二等卧"),
    SOFT_SLEEPER(6, "软卧"),
    HARD_SLEEPER(7, "硬卧"),
    HARD_SEAT(8, "硬座"),
    DELUXE_SOFT_SLEEPER(9, "高级软卧"),
    DINING_CAR_SLEEPER(10, "动卧"),
    SOFT_SEAT(11, "软座"),
    FIRST_CLASS_SEAT(12, "特等座"),
    NO_SEAT_SLEEPER(13, "无座"),
    OTHER(14, "其他");

    private final Integer code;

    private final String value;
}
