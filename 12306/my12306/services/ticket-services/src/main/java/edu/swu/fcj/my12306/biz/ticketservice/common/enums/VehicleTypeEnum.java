package edu.swu.fcj.my12306.biz.ticketservice.common.enums;

import cn.hutool.core.collection.ListUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum.BUSINESS_CLASS;
import static edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum.FIRST_CLASS;
import static edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum.FIRST_SLEEPER;
import static edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum.HARD_SEAT;
import static edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum.HARD_SLEEPER;
import static edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum.NO_SEAT_SLEEPER;
import static edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum.SECOND_CLASS;
import static edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum.SECOND_CLASS_CABIN_SEAT;
import static edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum.SECOND_SLEEPER;
import static edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum.SOFT_SLEEPER;

/**
 * 车次类型：决定该车次支持的席别集合
 */
@Getter
@RequiredArgsConstructor
public enum VehicleTypeEnum {

    /**
     * 高铁
     */
    HIGH_SPEED_RAIN(0, "高铁", ListUtil.of(
            BUSINESS_CLASS.getCode(), FIRST_CLASS.getCode(), SECOND_CLASS.getCode())),

    /**
     * 动车
     */
    BULLET(1, "动车", ListUtil.of(
            SECOND_CLASS_CABIN_SEAT.getCode(), FIRST_SLEEPER.getCode(), SECOND_SLEEPER.getCode(), NO_SEAT_SLEEPER.getCode())),

    /**
     * 普通车
     */
    REGULAR_TRAIN(2, "普通车", ListUtil.of(
            SOFT_SLEEPER.getCode(), HARD_SLEEPER.getCode(), HARD_SEAT.getCode(), NO_SEAT_SLEEPER.getCode())),

    /**
     * 汽车
     */
    CAR(3, "汽车", null),

    /**
     * 飞机
     */
    AIRPLANE(4, "飞机", null);

    private final Integer code;

    private final String name;

    private final List<Integer> seatTypes;

    /**
     * 根据车次类型查找席别集合
     */
    public static List<Integer> findSeatTypesByCode(Integer code) {
        return Arrays.stream(VehicleTypeEnum.values())
                .filter(each -> Objects.equals(each.getCode(), code))
                .findFirst()
                .map(VehicleTypeEnum::getSeatTypes)
                .orElse(Collections.emptyList());
    }
}
