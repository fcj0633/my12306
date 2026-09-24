package edu.swu.fcj.my12306.biz.ticketservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketAvailabilityTokenBucketTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private SeatMapper seatMapper;
    @Mock
    private TrainMapper trainMapper;
    @Mock
    private RedisCacheHelper redisCacheHelper;

    private SimpleMeterRegistry meterRegistry;
    private TicketAvailabilityTokenBucket tokenBucket;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tokenBucket = new TicketAvailabilityTokenBucket(
                stringRedisTemplate, seatMapper, trainMapper, redisCacheHelper, meterRegistry);
        ReflectionTestUtils.setField(tokenBucket, "enabled", true);
        ReflectionTestUtils.setField(tokenBucket, "expireMinutes", 10L);
    }

    @Test
    void disabled_doesNotAccessRedis() {
        ReflectionTestUtils.setField(tokenBucket, "enabled", false);

        assertTrue(tokenBucket.takeToken(1L, "北京南", "宁波", Map.of(0, 1)));
        tokenBucket.returnToken(1L, "北京南", "宁波", Map.of(0, 1));

        verifyNoInteractions(stringRedisTemplate, seatMapper, trainMapper, redisCacheHelper);
    }

    @Test
    void takeToken_recordsPassAndReject() {
        when(executeScript()).thenReturn(1L, 0L);

        assertTrue(tokenBucket.takeToken(1L, "北京南", "宁波", Map.of(0, 2, 2, 1)));
        assertFalse(tokenBucket.takeToken(1L, "北京南", "宁波", Map.of(0, 20)));

        assertEquals(1.0, meterRegistry.counter("my12306.token.pass").count());
        assertEquals(1.0, meterRegistry.counter("my12306.token.reject").count());
    }

    @Test
    void missingField_loadsAllVehicleSeatTypesAndRetriesOnce() {
        when(executeScript()).thenReturn(-1L, 1L);
        when(redisCacheHelper.hMultiGet(any(), anyList())).thenReturn(Arrays.asList((Object) null));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(redisCacheHelper).executeWithLock(any(), any(Runnable.class));
        when(seatMapper.selectMaps(any(QueryWrapper.class)))
                .thenReturn(List.of(Map.of("seat_type", 0, "cnt", 10L),
                        Map.of("seat_type", 2, "cnt", 810L)));
        TrainDO train = new TrainDO();
        train.setTrainType(0);
        when(trainMapper.selectById(1L)).thenReturn(train);

        assertTrue(tokenBucket.takeToken(1L, "北京南", "宁波", Map.of(0, 1)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> bucketCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redisCacheHelper).hPutAllWithTtl(any(), bucketCaptor.capture(), eq(10L), eq(TimeUnit.MINUTES));
        assertEquals(Map.of("0", "10", "1", "0", "2", "810"), bucketCaptor.getValue());
        assertEquals(1.0, meterRegistry.counter("my12306.token.load").count());
    }

    @Test
    void redisFailure_degradesToPass() {
        when(executeScript()).thenThrow(new IllegalStateException("redis unavailable"));

        assertTrue(tokenBucket.takeToken(1L, "北京南", "宁波", Map.of(0, 1)));

        assertEquals(1.0, meterRegistry.counter("my12306.token.degrade").count());
    }

    @Test
    void returnFailure_isSwallowedAndCountedAsDegrade() {
        when(executeScript()).thenThrow(new IllegalStateException("redis unavailable"));

        tokenBucket.returnToken(1L, "北京南", "宁波", Map.of(0, 1));

        assertEquals(1.0, meterRegistry.counter("my12306.token.degrade").count());
        verify(redisCacheHelper, never()).hPutAllWithTtl(any(), any(), anyLong(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Long executeScript() {
        return (Long) stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
