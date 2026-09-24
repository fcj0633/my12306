package edu.swu.fcj.my12306.biz.ticketservice.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.TicketStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TicketDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TicketMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.TicketCallbackSeatDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketCallbackReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.OrderRemoteService;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.OrderStatusRemoteRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.TicketCallbackService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * Reconciles old unpaid tickets by their durable order id. Age alone never releases a seat: the
 * order service must confirm that the order is absent/closed, or confirm payment for forward repair.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.ticket.orphan-scan-enabled", havingValue = "true", matchIfMissing = true)
public class TicketOrphanRecoveryJob {

    private static final int ORDER_PENDING = 0;
    private static final int ORDER_PAID = 10;
    private static final int ORDER_CLOSED = 30;

    private final TicketMapper ticketMapper;
    private final OrderRemoteService orderRemoteService;
    private final TicketCallbackService ticketCallbackService;
    private final MeterRegistry meterRegistry;

    @Value("${my12306.ticket.orphan-age-minutes:25}")
    private long orphanAgeMinutes;

    @Value("${my12306.ticket.orphan-scan-batch-size:100}")
    private int batchSize;

    @Scheduled(initialDelayString = "${my12306.ticket.orphan-scan-interval-ms:60000}",
            fixedDelayString = "${my12306.ticket.orphan-scan-interval-ms:60000}")
    public void recover() {
        Date before = Date.from(Instant.now().minus(orphanAgeMinutes, ChronoUnit.MINUTES));
        List<String> orderSns = ticketMapper.selectRecoverableOrderSns(
                TicketStatusEnum.UNPAID.getCode(), before, batchSize);
        for (String orderSn : orderSns) {
            recoverOne(orderSn);
        }
    }

    void recoverOne(String orderSn) {
        try {
            Result<OrderStatusRemoteRespDTO> result = orderRemoteService.queryOrderStatus(orderSn);
            if (result == null || !Result.SUCCESS_CODE.equals(result.getCode()) || result.getData() == null) {
                throw new IllegalStateException("订单状态响应无效");
            }
            OrderStatusRemoteRespDTO status = result.getData();
            if (Boolean.FALSE.equals(status.getExists()) || Integer.valueOf(ORDER_CLOSED).equals(status.getStatus())) {
                ticketCallbackService.cancelCallback(buildCallback(orderSn));
                meterRegistry.counter("my12306.purchase.recovery.cancel").increment();
            } else if (Integer.valueOf(ORDER_PAID).equals(status.getStatus())) {
                ticketCallbackService.payCallback(buildCallback(orderSn));
                meterRegistry.counter("my12306.purchase.recovery.pay").increment();
            } else if (!Integer.valueOf(ORDER_PENDING).equals(status.getStatus())) {
                log.warn("孤儿车票对应订单状态未知，本轮保持不动。orderSn={}，status={}", orderSn, status.getStatus());
            }
        } catch (Throwable ex) {
            meterRegistry.counter("my12306.purchase.recovery.fail").increment();
            log.warn("孤儿车票恢复失败，本轮不改变座位并等待重试。orderSn={}", orderSn, ex);
        }
    }

    private TicketCallbackReqDTO buildCallback(String orderSn) {
        List<TicketDO> tickets = ticketMapper.selectList(Wrappers.lambdaQuery(TicketDO.class)
                .eq(TicketDO::getOrderSn, orderSn)
                .eq(TicketDO::getTicketStatus, TicketStatusEnum.UNPAID.getCode()));
        if (tickets.isEmpty()) {
            throw new IllegalStateException("没有待恢复车票");
        }
        TicketDO first = tickets.getFirst();
        List<TicketCallbackSeatDTO> seats = tickets.stream().map(ticket -> {
            TicketCallbackSeatDTO seat = new TicketCallbackSeatDTO();
            seat.setCarriageNumber(ticket.getCarriageNumber());
            seat.setSeatNumber(ticket.getSeatNumber());
            seat.setSeatType(ticket.getSeatType());
            return seat;
        }).toList();
        TicketCallbackReqDTO callback = new TicketCallbackReqDTO();
        callback.setOrderSn(orderSn);
        callback.setTrainId(first.getTrainId());
        callback.setDeparture(first.getStartStation());
        callback.setArrival(first.getEndStation());
        callback.setSeats(seats);
        return callback;
    }
}
