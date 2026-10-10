package edu.swu.fcj.my12306.biz.ticketservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.common.Results;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.UserContext;
import edu.swu.fcj.my12306.biz.ticketservice.common.UserInfoDTO;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.SeatStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.TicketStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TicketDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TicketMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.OrderRemoteService;
import edu.swu.fcj.my12306.biz.ticketservice.remote.UserRemoteService;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

/**
 * P1 购票测试：真实 MySQL（票务库）+ Mock Redis/Redisson/Feign（不依赖 Redis、Nacos、用户服务、订单服务）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
                "my12306.pay.notify-mode=feign", "my12306.ticket.orphan-scan-enabled=false"})
class PurchaseTicketServiceTest {

    @SpyBean
    private edu.swu.fcj.my12306.biz.ticketservice.service.impl.PurchaseMetadataService metadataService;

    private static final String TEST_USERNAME = "p1-test-user";

    private static final String TEST_USER_ID = "1001";

    private static final String TRAIN_ID = "1";

    private static final String DEPARTURE = "北京南";

    private static final String ARRIVAL = "南京南";

    private long lockedBeforeTest;

    private Set<Long> ticketIdsBeforeTest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PurchaseTicketService purchaseTicketService;

    @SpyBean
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

    @BeforeEach
    void setUp() {
        lockedBeforeTest = countLockedSeats();
        ticketIdsBeforeTest = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT id FROM t_ticket WHERE username=?", Long.class, TEST_USERNAME));
        UserContext.setUser(UserInfoDTO.builder().userId(TEST_USER_ID).username(TEST_USERNAME).realName("测试用户").build());

        // 缓存：直接把回源结果返回（等价于缓存未命中回源），测试不连接 Redis
        when(redisCacheHelper.safeGet(anyString(), ArgumentMatchers.<Supplier<String>>any(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> ((Supplier<String>) invocation.getArgument(1)).get());

        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(tokenBucket.takeToken(anyLong(), anyString(), anyString(), anyMap())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
        // Include logically deleted tickets, but preserve every historical test row.
        // Mapper.delete is a logical delete, so it cannot remove test fixtures physically.
        jdbcTemplate.query("SELECT id,train_id,start_station,end_station,seat_type,carriage_number,seat_number "
                + "FROM t_ticket WHERE username=?", (rs, rowNum) -> {
            TicketDO ticket = new TicketDO();
            ticket.setId(rs.getLong("id"));
            ticket.setTrainId(rs.getLong("train_id"));
            ticket.setStartStation(rs.getString("start_station"));
            ticket.setEndStation(rs.getString("end_station"));
            ticket.setSeatType(rs.getInt("seat_type"));
            ticket.setCarriageNumber(rs.getString("carriage_number"));
            ticket.setSeatNumber(rs.getString("seat_number"));
            return ticket;
        }, TEST_USERNAME).stream().filter(ticket -> !ticketIdsBeforeTest.contains(ticket.getId())).forEach(ticket -> {
            jdbcTemplate.update("UPDATE t_seat SET seat_status=? WHERE train_id=? AND start_station=? "
                            + "AND end_station=? AND seat_type=? AND carriage_number=? AND seat_number=? "
                            + "AND seat_status=? AND del_flag=0",
                    SeatStatusEnum.AVAILABLE.getCode(), ticket.getTrainId(), ticket.getStartStation(),
                    ticket.getEndStation(), ticket.getSeatType(), ticket.getCarriageNumber(),
                    ticket.getSeatNumber(), SeatStatusEnum.LOCKED.getCode());
            jdbcTemplate.update("DELETE FROM t_ticket WHERE id=? AND username=?", ticket.getId(), TEST_USERNAME);
        });
    }

    @Test
    void purchaseTickets_success_locksSeatsWritesTicketsAndReturnsOrderSn() {
        stubPassengers(101L, 102L);
        when(orderRemoteService.createTicketOrder(any(TicketOrderCreateRemoteReqDTO.class)))
                .thenAnswer(invocation -> {
                    TicketOrderCreateRemoteReqDTO request = invocation.getArgument(0);
                    return new Result<String>().setCode(Result.SUCCESS_CODE).setData(request.getOrderSn());
                });

        TicketPurchaseRespDTO response = purchaseTicketService.purchaseTickets(
                buildRequest(2, List.of(101L, 102L)));

        assertNotNull(response.getOrderSn());
        assertEquals(2, response.getTicketOrderDetails().size());
        assertEquals(lockedBeforeTest + 2, countLockedSeats());
        assertEquals(2, countTestTickets());
    }

    @Test
    void purchaseTickets_notEnoughStock_throwsAndChangesNothing() {
        stubPassengers(101L);
        stubMetadataForInventoryFailure();

        // 令牌桶放行后，由事务内选座发现无座（13）没有库存并回滚。
        ServiceException exception = assertThrows(ServiceException.class,
                () -> purchaseTicketService.purchaseTickets(buildRequest(13, List.of(101L))));
        assertEquals(true, exception.getMessage().contains("站点余票不足"));
        assertEquals(0, countTestTickets());
        verify(tokenBucket).returnToken(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void purchaseTickets_mixedSeatTypes_shortageRollsBackEarlierReservation() {
        stubPassengers(101L, 102L);
        stubMetadataForInventoryFailure();
        long lockedBefore = countLockedSeats();
        PurchaseTicketReqDTO request = buildRequest(2, List.of(101L, 102L));
        request.getPassengers().get(1).setSeatType(13);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> purchaseTicketService.purchaseTickets(request));

        assertEquals(true, exception.getMessage().contains("站点余票不足"));
        assertEquals(lockedBefore, countLockedSeats());
        assertEquals(0, countTestTickets());
        verify(tokenBucket).returnToken(anyLong(), anyString(), anyString(), anyMap());
    }

    private void stubMetadataForInventoryFailure() {
        // Type 13 has neither price nor seats. Provide valid request metadata to
        // isolate the inventory rollback assertion without mutating real prices.
        doReturn(new edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseMetadata(
                "G1", java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.util.Map.of(2, 100, 13, 100)))
                .when(metadataService).prepare(any());
    }

    @Test
    void purchaseTickets_conditionalUpdateLosesRace_rollsBackAndReturnsToken() {
        stubPassengers(101L);
        long lockedBefore = countLockedSeats();
        doReturn(0).when(seatMapper).update(any(SeatDO.class), ArgumentMatchers.<Wrapper<SeatDO>>any());

        ServiceException exception = assertThrows(ServiceException.class,
                () -> purchaseTicketService.purchaseTickets(buildRequest(2, List.of(101L))));

        assertEquals(true, exception.getMessage().contains("站点余票不足"));
        assertEquals(lockedBefore, countLockedSeats());
        assertEquals(0, countTestTickets());
        verify(tokenBucket).returnToken(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void purchaseTickets_orderServiceFailure_compensatesSeatAndTicket() {
        stubPassengers(101L);
        when(orderRemoteService.createTicketOrder(any(TicketOrderCreateRemoteReqDTO.class)))
                .thenReturn(new Result<String>().setCode("500").setMessage("订单服务异常"));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> purchaseTicketService.purchaseTickets(buildRequest(2, List.of(101L))));
        assertEquals("订单服务拒绝创建订单，座位已释放", exception.getMessage());
        // 本地占座事务已经提交，随后由幂等取消回调释放座位并取消车票。
        assertEquals(lockedBeforeTest, countLockedSeats());
        assertEquals(0, countTestTickets());
        verify(tokenBucket).returnToken(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void purchaseTickets_passengerNotBelongToUser_throws() {
        // 请求 2 位乘车人，但用户服务只返回 1 位
        stubPassengers(101L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> purchaseTicketService.purchaseTickets(buildRequest(2, List.of(101L, 102L))));
        assertEquals("乘车人不存在或不属于当前用户", exception.getMessage());
        assertEquals(0, countTestTickets());
    }

    @Test
    void purchaseTickets_missingTrainId_throws() {
        PurchaseTicketReqDTO requestParam = buildRequest(2, List.of(101L));
        requestParam.setTrainId(null);
        ServiceException exception = assertThrows(ServiceException.class,
                () -> purchaseTicketService.purchaseTickets(requestParam));
        assertEquals("列车标识不能为空", exception.getMessage());
    }

    private PurchaseTicketReqDTO buildRequest(Integer seatType, List<Long> passengerIds) {
        PurchaseTicketReqDTO requestParam = new PurchaseTicketReqDTO();
        requestParam.setTrainId(TRAIN_ID);
        requestParam.setDeparture(DEPARTURE);
        requestParam.setArrival(ARRIVAL);
        List<PurchaseTicketPassengerDetailDTO> passengers = new ArrayList<>();
        for (Long passengerId : passengerIds) {
            PurchaseTicketPassengerDetailDTO passenger = new PurchaseTicketPassengerDetailDTO();
            passenger.setPassengerId(String.valueOf(passengerId));
            passenger.setSeatType(seatType);
            passengers.add(passenger);
        }
        requestParam.setPassengers(passengers);
        return requestParam;
    }

    private void stubPassengers(Long... passengerIds) {
        List<PassengerActualRespDTO> passengers = new ArrayList<>();
        for (Long passengerId : passengerIds) {
            PassengerActualRespDTO passenger = new PassengerActualRespDTO();
            passenger.setId(String.valueOf(passengerId));
            passenger.setUsername(TEST_USERNAME);
            passenger.setRealName("乘车人" + passengerId);
            passenger.setIdType(1);
            passenger.setIdCard("11010119900101" + passengerId);
            passenger.setDiscountType(0);
            passenger.setPhone("13800000000");
            passengers.add(passenger);
        }
        when(userRemoteService.listPassengerQueryByIds(anyString(), anyList())).thenReturn(Results.success(passengers));
    }

    private long countLockedSeats() {
        return seatMapper.selectCount(Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, Long.valueOf(TRAIN_ID))
                .eq(SeatDO::getStartStation, DEPARTURE)
                .eq(SeatDO::getEndStation, ARRIVAL)
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.LOCKED.getCode()));
    }

    private long countTestTickets() {
        return ticketMapper.selectCount(Wrappers.lambdaQuery(TicketDO.class)
                .eq(TicketDO::getUsername, TEST_USERNAME)
                .eq(TicketDO::getTicketStatus, TicketStatusEnum.UNPAID.getCode()));
    }
}
