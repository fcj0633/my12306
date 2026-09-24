package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.UserContext;
import edu.swu.fcj.my12306.biz.ticketservice.common.UserInfoDTO;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.chain.AbstractChainContext;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseReservationResult;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketCallbackReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.OrderRemoteService;
import edu.swu.fcj.my12306.biz.ticketservice.remote.UserRemoteService;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.PurchaseTicketServiceImpl;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.PurchaseTicketTxService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseTicketOrchestrationTest {

    @Mock private RedissonClient redissonClient;
    @Mock private AbstractChainContext chainContext;
    @Mock private TicketAvailabilityTokenBucket tokenBucket;
    @Mock private PurchaseTicketTxService txService;
    @Mock private UserRemoteService userRemoteService;
    @Mock private OrderRemoteService orderRemoteService;
    @Mock private TicketCallbackService ticketCallbackService;
    @Mock private RedisCacheHelper redisCacheHelper;
    @Mock private RLock userLock;
    @Mock private RLock seatType0Lock;
    @Mock private RLock seatType2Lock;

    private PurchaseTicketServiceImpl purchaseService;

    @BeforeEach
    void setUp() {
        purchaseService = new PurchaseTicketServiceImpl(redissonClient, chainContext, tokenBucket, txService,
                userRemoteService, orderRemoteService, ticketCallbackService, redisCacheHelper,
                new SimpleMeterRegistry());
        UserContext.setUser(UserInfoDTO.builder().userId("1001").username("tester").build());
        when(tokenBucket.takeToken(eq(1L), anyString(), anyString(), anyMap())).thenReturn(true);
        when(redissonClient.getLock("my12306-ticket-service:lock:purchase_tickets_user_tester_1"))
                .thenReturn(userLock);
        when(redissonClient.getLock("my12306-ticket-service:lock:purchase_tickets_1_0"))
                .thenReturn(seatType0Lock);
        lenient().when(redissonClient.getLock("my12306-ticket-service:lock:purchase_tickets_1_2"))
                .thenReturn(seatType2Lock);
        when(userLock.isHeldByCurrentThread()).thenReturn(true);
        when(seatType0Lock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(seatType2Lock.isHeldByCurrentThread()).thenReturn(true);
        when(userRemoteService.listPassengerQueryByIds(eq("tester"), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Long> ids = invocation.getArgument(1);
            List<PassengerActualRespDTO> passengers = ids.stream().map(id -> {
                PassengerActualRespDTO passenger = new PassengerActualRespDTO();
                passenger.setId(String.valueOf(id));
                return passenger;
            }).toList();
            return new Result<List<PassengerActualRespDTO>>().setCode(Result.SUCCESS_CODE).setData(passengers);
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void remoteCallsStayOutsideSeatLocks() {
        PurchaseTicketReqDTO request = requestWithSeatTypes(2, 0);
        stubReservationAndSuccessfulOrder();

        purchaseService.purchaseTickets(request);

        InOrder order = inOrder(tokenBucket, userRemoteService, userLock, seatType0Lock,
                seatType2Lock, txService, orderRemoteService);
        order.verify(tokenBucket).takeToken(1L, "北京南", "宁波", Map.of(0, 1, 2, 1));
        order.verify(userRemoteService).listPassengerQueryByIds(eq("tester"), any());
        order.verify(userLock).lock();
        order.verify(seatType0Lock).lock();
        order.verify(seatType2Lock).lock();
        order.verify(txService).doPurchaseInTransaction(eq(request), eq("1001"), eq("tester"), anyString(), anyMap());
        order.verify(seatType2Lock).unlock();
        order.verify(seatType0Lock).unlock();
        order.verify(userLock).unlock();
        order.verify(orderRemoteService).createTicketOrder(any());
        verify(tokenBucket, never()).returnToken(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void transactionFailureReturnsTokensOnce() {
        PurchaseTicketReqDTO request = requestWithSeatTypes(0, 2);
        when(txService.doPurchaseInTransaction(eq(request), eq("1001"), eq("tester"), anyString(), anyMap()))
                .thenThrow(new ServiceException("本地事务失败"));

        assertThrows(ServiceException.class, () -> purchaseService.purchaseTickets(request));

        verify(tokenBucket).returnToken(1L, "北京南", "宁波", Map.of(0, 1, 2, 1));
        verify(ticketCallbackService, never()).cancelCallback(any());
    }

    @Test
    void definiteOrderFailureCompensatesWithoutSecondTokenReturn() {
        PurchaseTicketReqDTO request = requestWithSeatTypes(0);
        stubReservation();
        when(orderRemoteService.createTicketOrder(any()))
                .thenReturn(new Result<String>().setCode("500").setMessage("rejected"));

        assertThrows(ServiceException.class, () -> purchaseService.purchaseTickets(request));

        verify(ticketCallbackService).cancelCallback(any());
        verify(tokenBucket, never()).returnToken(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void ambiguousOrderFailureDoesNotReleaseSeatOrToken() {
        PurchaseTicketReqDTO request = requestWithSeatTypes(0);
        stubReservation();
        when(orderRemoteService.createTicketOrder(any())).thenThrow(new RuntimeException("timeout"));

        assertThrows(ServiceException.class, () -> purchaseService.purchaseTickets(request));

        verify(ticketCallbackService, never()).cancelCallback(any());
        verify(tokenBucket, never()).returnToken(any(), anyString(), anyString(), anyMap());
    }

    private void stubReservationAndSuccessfulOrder() {
        stubReservation();
        when(orderRemoteService.createTicketOrder(any())).thenAnswer(invocation -> {
            TicketOrderCreateRemoteReqDTO request = invocation.getArgument(0);
            return new Result<String>().setCode(Result.SUCCESS_CODE).setData(request.getOrderSn());
        });
    }

    private void stubReservation() {
        when(txService.doPurchaseInTransaction(any(), anyString(), anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> {
                    String orderSn = invocation.getArgument(3);
                    TicketOrderCreateRemoteReqDTO orderRequest = TicketOrderCreateRemoteReqDTO.builder()
                            .orderSn(orderSn)
                            .build();
                    TicketCallbackReqDTO callback = new TicketCallbackReqDTO();
                    callback.setOrderSn(orderSn);
                    return new PurchaseReservationResult(orderRequest, List.of(), callback);
                });
    }

    private PurchaseTicketReqDTO requestWithSeatTypes(Integer... seatTypes) {
        PurchaseTicketReqDTO request = new PurchaseTicketReqDTO();
        request.setTrainId("1");
        request.setDeparture("北京南");
        request.setArrival("宁波");
        request.setPassengers(Arrays.stream(seatTypes).map(seatType -> {
            PurchaseTicketPassengerDetailDTO passenger = new PurchaseTicketPassengerDetailDTO();
            passenger.setPassengerId("100" + seatType);
            passenger.setSeatType(seatType);
            return passenger;
        }).toList());
        return request;
    }
}
