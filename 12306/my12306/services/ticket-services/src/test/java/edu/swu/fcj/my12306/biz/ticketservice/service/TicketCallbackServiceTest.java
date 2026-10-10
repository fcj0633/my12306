package edu.swu.fcj.my12306.biz.ticketservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.SeatStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.TicketStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TicketDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TicketMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.TicketCallbackSeatDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketCallbackReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.OrderRemoteService;
import edu.swu.fcj.my12306.biz.ticketservice.remote.UserRemoteService;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P2 票务回调测试：真实 MySQL（票务库）+ Mock Redis/Redisson/Feign
 * <p>
 * 测试现场统一构造为"P1 购票之后"的样子：座位=已锁定(1)、车票=未支付(0)，
 * 然后验证支付回调与取消回调分别把状态推到哪一步。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
                "my12306.pay.notify-mode=feign", "my12306.ticket.orphan-scan-enabled=false"})
class TicketCallbackServiceTest {

    private static final String TEST_USERNAME = "p2-callback-test-user";
    private static final String TEST_ORDER_SN = "P2-CALLBACK-TEST-ORDER";

    private static final String NEW_ORDER_SN = "P2-CALLBACK-NEW-ORDER";

    private static final String NEW_USERNAME = "p2-callback-new-owner";

    private static final Long TRAIN_ID = 1L;

    private static final String DEPARTURE = "北京南";

    private static final String ARRIVAL = "南京南";

    private static final Integer SEAT_TYPE = 2;

    @Autowired
    private TicketCallbackService ticketCallbackService;

    @Autowired
    private SeatMapper seatMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @MockBean
    private RedisCacheHelper redisCacheHelper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RedissonClient redissonClient;

    @MockBean
    private UserRemoteService userRemoteService;

    @MockBean
    private OrderRemoteService orderRemoteService;

    @MockBean
    private TicketAvailabilityTokenBucket tokenBucket;

    private SeatDO seat;

    @BeforeEach
    void setUp() {
        seat = seatMapper.selectOne(Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, TRAIN_ID)
                .eq(SeatDO::getSeatType, SEAT_TYPE)
                .eq(SeatDO::getStartStation, DEPARTURE)
                .eq(SeatDO::getEndStation, ARRIVAL)
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                .last("limit 1"));
        updateSeatStatus(SeatStatusEnum.LOCKED.getCode());
        ticketMapper.insert(buildUnpaidTicket());
    }

    @AfterEach
    void tearDown() {
        updateSeatStatus(SeatStatusEnum.AVAILABLE.getCode());
        ticketMapper.delete(Wrappers.lambdaQuery(TicketDO.class)
                .in(TicketDO::getUsername, TEST_USERNAME, NEW_USERNAME));
    }

    @Test
    void payCallback_movesSeatToSoldAndTicketToPaid() {
        ticketCallbackService.payCallback(buildCallbackRequest());

        assertEquals(SeatStatusEnum.SOLD.getCode(), currentSeatStatus());
        assertEquals(TicketStatusEnum.PAID.getCode(), currentTicketStatus());
        verify(tokenBucket, never()).returnToken(eq(TRAIN_ID), eq(DEPARTURE), eq(ARRIVAL), anyMap());
    }

    @Test
    void cancelCallback_releasesSeatAndCancelsTicket() {
        ticketCallbackService.cancelCallback(buildCallbackRequest());

        assertEquals(SeatStatusEnum.AVAILABLE.getCode(), currentSeatStatus());
        assertEquals(TicketStatusEnum.CANCELED.getCode(), currentTicketStatus());
        verify(tokenBucket).returnToken(TRAIN_ID, DEPARTURE, ARRIVAL, java.util.Map.of(SEAT_TYPE, 1));
    }

    @Test
    void payCallback_repeatedCallDoesNotChangeAnything() {
        ticketCallbackService.payCallback(buildCallbackRequest());
        // 跨服务通知可能重复投递：第二次调用必须无副作用、也不能报错
        ticketCallbackService.payCallback(buildCallbackRequest());

        assertEquals(SeatStatusEnum.SOLD.getCode(), currentSeatStatus());
        assertEquals(TicketStatusEnum.PAID.getCode(), currentTicketStatus());
    }

    @Test
    void cancelCallback_doesNotReleaseAlreadySoldSeat() {
        updateSeatStatus(SeatStatusEnum.SOLD.getCode());

        ticketCallbackService.cancelCallback(buildCallbackRequest());

        // 已出售的座位绝不能被取消链路放回可售，否则等于把票卖两次
        assertEquals(SeatStatusEnum.SOLD.getCode(), currentSeatStatus());
        assertEquals(TicketStatusEnum.UNPAID.getCode(), currentTicketStatus());
    }

    @Test
    void cancelCallback_repeatedCallDoesNotChangeAnything() {
        ticketCallbackService.cancelCallback(buildCallbackRequest());
        ticketCallbackService.cancelCallback(buildCallbackRequest());

        assertEquals(SeatStatusEnum.AVAILABLE.getCode(), currentSeatStatus());
        assertEquals(TicketStatusEnum.CANCELED.getCode(), currentTicketStatus());
        verify(tokenBucket, times(1)).returnToken(
                TRAIN_ID, DEPARTURE, ARRIVAL, java.util.Map.of(SEAT_TYPE, 1));
    }

    @Test
    void staleCancelCallback_doesNotReleaseSeatReservedByNewOrder() {
        ticketCallbackService.cancelCallback(buildCallbackRequest());

        updateSeatStatus(SeatStatusEnum.LOCKED.getCode());
        TicketDO newOwnerTicket = buildUnpaidTicket();
        newOwnerTicket.setOrderSn(NEW_ORDER_SN);
        newOwnerTicket.setUsername(NEW_USERNAME);
        ticketMapper.insert(newOwnerTicket);

        // A delayed retry from the old order must not release the same coordinates again.
        ticketCallbackService.cancelCallback(buildCallbackRequest());

        assertEquals(SeatStatusEnum.LOCKED.getCode(), currentSeatStatus());
        assertEquals(TicketStatusEnum.CANCELED.getCode(), currentTicketStatus());
        assertEquals(TicketStatusEnum.UNPAID.getCode(), currentTicketStatus(NEW_USERNAME));
        verify(tokenBucket, times(1)).returnToken(
                TRAIN_ID, DEPARTURE, ARRIVAL, java.util.Map.of(SEAT_TYPE, 1));
    }

    private TicketCallbackReqDTO buildCallbackRequest() {
        TicketCallbackSeatDTO seatDTO = new TicketCallbackSeatDTO();
        seatDTO.setCarriageNumber(seat.getCarriageNumber());
        seatDTO.setSeatNumber(seat.getSeatNumber());
        seatDTO.setSeatType(seat.getSeatType());

        TicketCallbackReqDTO requestParam = new TicketCallbackReqDTO();
        requestParam.setOrderSn(TEST_ORDER_SN);
        requestParam.setTrainId(TRAIN_ID);
        requestParam.setDeparture(DEPARTURE);
        requestParam.setArrival(ARRIVAL);
        requestParam.setSeats(List.of(seatDTO));
        return requestParam;
    }

    private TicketDO buildUnpaidTicket() {
        Date now = new Date();
        TicketDO ticketDO = TicketDO.builder()
                .orderSn(TEST_ORDER_SN)
                .username(TEST_USERNAME)
                .trainId(TRAIN_ID)
                .carriageNumber(seat.getCarriageNumber())
                .seatNumber(seat.getSeatNumber())
                .seatType(seat.getSeatType())
                .startStation(DEPARTURE)
                .endStation(ARRIVAL)
                .passengerId("1001")
                .ticketStatus(TicketStatusEnum.UNPAID.getCode())
                .build();
        ticketDO.setCreateTime(now);
        ticketDO.setUpdateTime(now);
        ticketDO.setDelFlag(0);
        return ticketDO;
    }

    private void updateSeatStatus(Integer seatStatus) {
        seatMapper.update(null, Wrappers.lambdaUpdate(SeatDO.class)
                .eq(SeatDO::getId, seat.getId())
                .set(SeatDO::getSeatStatus, seatStatus));
    }

    private Integer currentSeatStatus() {
        return seatMapper.selectById(seat.getId()).getSeatStatus();
    }

    private Integer currentTicketStatus() {
        return currentTicketStatus(TEST_USERNAME);
    }

    private Integer currentTicketStatus(String username) {
        TicketDO ticketDO = ticketMapper.selectOne(Wrappers.lambdaQuery(TicketDO.class)
                .eq(TicketDO::getUsername, username)
                .eq(TicketDO::getCarriageNumber, seat.getCarriageNumber())
                .eq(TicketDO::getSeatNumber, seat.getSeatNumber()));
        return ticketDO == null ? null : ticketDO.getTicketStatus();
    }
}
