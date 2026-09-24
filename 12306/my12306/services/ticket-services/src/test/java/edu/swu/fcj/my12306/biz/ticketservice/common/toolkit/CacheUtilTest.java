package edu.swu.fcj.my12306.biz.ticketservice.common.toolkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheUtilTest {

    @Test
    void buildKey_joinsByUnderscore() {
        assertEquals("1_北京南_南京南", CacheUtil.buildKey("1", "北京南", "南京南"));
    }
}
