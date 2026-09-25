package edu.swu.fcj.my12306.biz.ticketservice.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.common.concurrent.ScheduledLockExecutor;
import edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant;
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
    private final ScheduledLockExecutor scheduledLockExecutor;

    @Value("${my12306.ticket.orphan-age-minutes:25}")
    private long orphanAgeMinutes;

    @Value("${my12306.ticket.orphan-scan-batch-size:100}")
    private int batchSize;

    /** 同时用于调度间隔与锁租约：租约覆盖整轮，避免同一轮被两个实例各执行一次。 */
    @Value("${my12306.ticket.orphan-scan-interval-ms:60000}")
    private long orphanScanIntervalMs;

    /** P2-3：实例标识。加锁前这一行会在两个 JVM 的日志里同时出现；加锁后只出现在认领本轮的那个。 */
    @Value("${server.port:0}")
    private int instancePort;

    @Scheduled(initialDelayString = "${my12306.ticket.orphan-scan-interval-ms:60000}",
            fixedDelayString = "${my12306.ticket.orphan-scan-interval-ms:60000}")
    public void recover() {
        scheduledLockExecutor.runWithLock(RedisKeyConstant.LOCK_JOB_TICKET_ORPHAN_RECOVERY,
                orphanScanIntervalMs, this::doRecover);
    }

    void doRecover() {
        Date before = Date.from(Instant.now().minus(orphanAgeMinutes, ChronoUnit.MINUTES));
        List<String> orderSns = ticketMapper.selectRecoverableOrderSns(
                TicketStatusEnum.UNPAID.getCode(), before, batchSize);
        // 观测点：@Scheduled 是进程内的，不加锁时每个 JVM 都会各跑一次。
        // 注意：可扫描集合实测为 0 行，所以"是否重复执行"只能靠这行日志判断，不能靠处理单量。
        log.info("[instance={}] TicketOrphanRecoveryJob.recover 本轮执行，可扫描 orderSn 数={}",
                instancePort, orderSns.size());
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
