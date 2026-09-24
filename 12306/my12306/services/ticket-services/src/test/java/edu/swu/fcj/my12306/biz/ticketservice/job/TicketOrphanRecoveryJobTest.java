package edu.swu.fcj.my12306.biz.ticketservice.job;

import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.TicketStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TicketDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TicketMapper;
import edu.swu.fcj.my12306.biz.ticketservice.remote.OrderRemoteService;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.OrderStatusRemoteRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.TicketCallbackService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrphanRecoveryJobTest {

    @Mock private TicketMapper ticketMapper;
    @Mock private OrderRemoteService orderRemoteService;
    @Mock private TicketCallbackService ticketCallbackService;

    private TicketOrphanRecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new TicketOrphanRecoveryJob(ticketMapper, orderRemoteService,
                ticketCallbackService, new SimpleMeterRegistry());
        lenient().when(ticketMapper.selectList(any())).thenReturn(List.of(ticket()));
    }

    @Test
    void confirmedMissingOrderCancelsReservation() {
        when(orderRemoteService.queryOrderStatus("ORDER-1")).thenReturn(status(false, null));

        job.recoverOne("ORDER-1");

        verify(ticketCallbackService).cancelCallback(any());
        verify(ticketCallbackService, never()).payCallback(any());
    }

    @Test
    void paidOrderRepairsTicketForward() {
        when(orderRemoteService.queryOrderStatus("ORDER-1")).thenReturn(status(true, 10));

        job.recoverOne("ORDER-1");

        verify(ticketCallbackService).payCallback(any());
        verify(ticketCallbackService, never()).cancelCallback(any());
    }

    @Test
    void closedOrderRepeatedScanUsesIdempotentCancellation() {
        when(orderRemoteService.queryOrderStatus("ORDER-1")).thenReturn(status(true, 30));

        job.recoverOne("ORDER-1");
        job.recoverOne("ORDER-1");

        verify(ticketCallbackService, times(2)).cancelCallback(any());
        verify(ticketCallbackService, never()).payCallback(any());
    }

    @Test
    void pendingOrUnavailableOrderNeverReleasesSeat() {
        when(orderRemoteService.queryOrderStatus("ORDER-1"))
                .thenReturn(status(true, 0))
                .thenThrow(new RuntimeException("unavailable"));

        job.recoverOne("ORDER-1");
        job.recoverOne("ORDER-1");

        verify(ticketCallbackService, never()).cancelCallback(any());
        verify(ticketCallbackService, never()).payCallback(any());
    }

    private Result<OrderStatusRemoteRespDTO> status(boolean exists, Integer value) {
        OrderStatusRemoteRespDTO data = new OrderStatusRemoteRespDTO();
        data.setExists(exists);
        data.setStatus(value);
        return new Result<OrderStatusRemoteRespDTO>().setCode(Result.SUCCESS_CODE).setData(data);
    }

    private TicketDO ticket() {
        return TicketDO.builder()
                .orderSn("ORDER-1")
                .trainId(1L)
                .startStation("北京南")
                .endStation("宁波")
                .carriageNumber("01")
                .seatNumber("01A")
                .seatType(0)
                .ticketStatus(TicketStatusEnum.UNPAID.getCode())
                .build();
    }
}
