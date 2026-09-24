package edu.swu.fcj.my12306.biz.ticketservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 购票来源
 */
@Getter
@RequiredArgsConstructor
public enum SourceEnum {

    /**
     * 互联网购票
     */
    INTERNET(0);

    private final Integer code;
}
