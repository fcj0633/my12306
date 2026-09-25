package edu.swu.fcj.my12306.biz.ticketservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.UserContext;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.id.SnowflakeIdGenerator;
import edu.swu.fcj.my12306.biz.ticketservice.common.chain.AbstractChainContext;
import edu.swu.fcj.my12306.biz.ticketservice.common.constant.TicketChainMarkEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.CacheUtil;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseReservationResult;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.OrderRemoteService;
import edu.swu.fcj.my12306.biz.ticketservice.remote.UserRemoteService;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.PurchaseTicketService;
import edu.swu.fcj.my12306.biz.ticketservice.service.TicketCallbackService;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_PURCHASE_TICKETS_SEAT_TYPE;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_PURCHASE_TICKETS_USER;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * D4 purchase orchestration. Admission and passenger ownership checks happen before locking;
 * the seat locks cover only the local ticket transaction, and order creation happens after every
 * lock is released. Once that transaction commits, tokens belong to the reservation lifecycle and
 * may only be returned by an actual seat release.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseTicketServiceImpl implements PurchaseTicketService {

    private final RedissonClient redissonClient;
    private final AbstractChainContext purchaseTicketAbstractChainContext;
    private final TicketAvailabilityTokenBucket tokenBucket;
    private final PurchaseTicketTxService purchaseTicketTxService;
    private final UserRemoteService userRemoteService;
    private final OrderRemoteService orderRemoteService;
    private final TicketCallbackService ticketCallbackService;
    private final RedisCacheHelper redisCacheHelper;
    private final MeterRegistry meterRegistry;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /** P2-2：实例标识，用于在日志里区分是哪个 JVM 拿到的锁。 */
    @Value("${server.port:0}")
    private int instancePort;

    @Override
    public TicketPurchaseRespDTO purchaseTickets(PurchaseTicketReqDTO requestParam) {
        purchaseTicketAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);
        String userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        if (StrUtil.isBlank(userId) || StrUtil.isBlank(username)) {
            throw new ServiceException("请先登录后再购票");
        }
        if (requestParam.getPassengers().size() > 5) {
            throw new ServiceException("单笔订单乘车人不能超过 5 位");
        }

        Map<Integer, Integer> seatTypeCounts = buildSeatTypeCounts(requestParam);
        boolean tokenTaken = tokenBucket.takeToken(Long.valueOf(requestParam.getTrainId()),
                requestParam.getDeparture(), requestParam.getArrival(), seatTypeCounts);
        if (!tokenTaken) {
            throw new ServiceException("列车站点已无余票");
        }

        boolean reservationCommitted = false;
        try {
            // Reject invalid passenger ownership before a request can occupy either distributed lock.
            Map<String, PassengerActualRespDTO> passengersById = loadPassengers(requestParam, username);
            // P2-5：orderSn 改为由显式分配 workerId 的生成器产出，不再用 Hutool 的进程级默认单例。
            String orderSn = snowflakeIdGenerator.nextId();
            PurchaseReservationResult reservation = reserveLocally(
                    requestParam, userId, username, orderSn, passengersById, seatTypeCounts);
            reservationCommitted = true;

            evictRemainingTicketCacheSafely(requestParam);
            Result<String> orderResult = createOrder(reservation, username, requestParam.getTrainId());
            if (orderResult == null || !Result.SUCCESS_CODE.equals(orderResult.getCode())) {
                compensateKnownFailure(reservation);
                throw new ServiceException("订单服务拒绝创建订单，座位已释放");
            }
            if (!StrUtil.equals(orderSn, orderResult.getData())) {
                // A malformed success response is ambiguous: the order may already exist. Recovery
                // will reconcile it by orderSn; releasing here could make a paid seat sellable.
                meterRegistry.counter("my12306.purchase.order.ambiguous").increment();
                throw new ServiceException("订单创建结果未知，系统将自动核对");
            }
            meterRegistry.counter("my12306.purchase.order.success").increment();
            return TicketPurchaseRespDTO.builder()
                    .orderSn(orderSn)
                    .ticketOrderDetails(reservation.ticketOrderDetails())
                    .build();
        } finally {
            // After the local transaction commits, cancelCallback is the sole token-return owner.
            if (!reservationCommitted) {
                tokenBucket.returnToken(Long.valueOf(requestParam.getTrainId()),
                        requestParam.getDeparture(), requestParam.getArrival(), seatTypeCounts);
            }
        }
    }

    private PurchaseReservationResult reserveLocally(
            PurchaseTicketReqDTO requestParam, String userId, String username, String orderSn,
            Map<String, PassengerActualRespDTO> passengersById, Map<Integer, Integer> seatTypeCounts) {
        RLock userLock = redissonClient.getLock(String.format(
                LOCK_PURCHASE_TICKETS_USER, username, requestParam.getTrainId()));
        boolean userLockAcquired = false;
        List<RLock> seatTypeLocks = new ArrayList<>();
        Timer.Sample lockSample = null;
        try {
            userLock.lock();
            userLockAcquired = true;
            for (Integer seatType : seatTypeCounts.keySet()) {
                RLock seatTypeLock = redissonClient.getLock(String.format(
                        LOCK_PURCHASE_TICKETS_SEAT_TYPE, requestParam.getTrainId(), seatType));
                seatTypeLock.lock();
                seatTypeLocks.add(seatTypeLock);
            }
            // P2-2 观测点：两个 ticket 实例必须打印出完全相同的锁 key，否则锁不互斥、会超卖。
            // 这两个 key 全部由 RedisKeyConstant 的常量 + String.format 构造，不含端口/主机名等实例标识。
            log.info("[instance={}] 购票锁已获取 userLock={} seatTypeLocks={} orderSn={}",
                    instancePort, userLock.getName(),
                    seatTypeLocks.stream().map(RLock::getName).toList(), orderSn);
            lockSample = Timer.start(meterRegistry);
            return purchaseTicketTxService.doPurchaseInTransaction(
                    requestParam, userId, username, orderSn, passengersById);
        } finally {
            if (lockSample != null) {
                try {
                    lockSample.stop(meterRegistry.timer("my12306.purchase.seat-lock.hold"));
                } catch (RuntimeException ex) {
                    // Observability must never change reservation or token ownership semantics.
                    log.warn("席别锁临界区计时上报失败。orderSn={}", orderSn, ex);
                }
            }
            for (int index = seatTypeLocks.size() - 1; index >= 0; index--) {
                unlockSafely(seatTypeLocks.get(index), "席别锁");
            }
            if (userLockAcquired) {
                unlockSafely(userLock, "用户锁");
            }
        }
    }

    private Map<String, PassengerActualRespDTO> loadPassengers(
            PurchaseTicketReqDTO requestParam, String username) {
        List<Long> passengerIds = requestParam.getPassengers().stream()
                .map(PurchaseTicketPassengerDetailDTO::getPassengerId)
                .map(Long::valueOf)
                .distinct()
                .toList();
        Timer.Sample sample = Timer.start(meterRegistry);
        Result<List<PassengerActualRespDTO>> result;
        try {
            result = userRemoteService.listPassengerQueryByIds(username, passengerIds);
        } catch (Throwable ex) {
            log.error("用户服务查询乘车人失败，username={}，ids={}", username, passengerIds, ex);
            throw new ServiceException("用户服务查询乘车人失败");
        } finally {
            sample.stop(meterRegistry.timer("my12306.purchase.passenger.remote"));
        }
        if (result == null || !Result.SUCCESS_CODE.equals(result.getCode()) || CollUtil.isEmpty(result.getData())) {
            throw new ServiceException("乘车人不存在或不属于当前用户");
        }
        Map<String, PassengerActualRespDTO> passengersById = result.getData().stream()
                .collect(Collectors.toMap(PassengerActualRespDTO::getId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        boolean complete = passengersById.size() == passengerIds.size()
                && requestParam.getPassengers().stream()
                .allMatch(each -> passengersById.containsKey(each.getPassengerId()));
        if (!complete) {
            throw new ServiceException("乘车人不存在或不属于当前用户");
        }
        return passengersById;
    }

    private Result<String> createOrder(
            PurchaseReservationResult reservation, String username, String trainId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return orderRemoteService.createTicketOrder(reservation.orderCreateRequest());
        } catch (Throwable ex) {
            meterRegistry.counter("my12306.purchase.order.ambiguous").increment();
            log.error("订单创建发生不确定远程错误，不释放座位，等待按 orderSn 恢复。username={}，trainId={}，orderSn={}",
                    username, trainId, reservation.orderCreateRequest().getOrderSn(), ex);
            throw new ServiceException("订单创建结果未知，系统将自动核对");
        } finally {
            sample.stop(meterRegistry.timer("my12306.purchase.order.remote"));
        }
    }

    private void compensateKnownFailure(PurchaseReservationResult reservation) {
        try {
            ticketCallbackService.cancelCallback(reservation.compensationRequest());
            meterRegistry.counter("my12306.purchase.compensation.success").increment();
        } catch (Throwable ex) {
            meterRegistry.counter("my12306.purchase.compensation.fail").increment();
            log.error("建单明确失败后的本地补偿失败，等待恢复任务。orderSn={}",
                    reservation.orderCreateRequest().getOrderSn(), ex);
        }
    }

    private void evictRemainingTicketCacheSafely(PurchaseTicketReqDTO requestParam) {
        try {
            redisCacheHelper.delete(TRAIN_STATION_REMAINING_TICKET
                    + CacheUtil.buildKey(requestParam.getTrainId(),
                    requestParam.getDeparture(), requestParam.getArrival()));
        } catch (RuntimeException ex) {
            log.warn("本地占座已提交，但余票缓存删除失败，等待缓存过期。trainId={}", requestParam.getTrainId(), ex);
        }
    }

    private Map<Integer, Integer> buildSeatTypeCounts(PurchaseTicketReqDTO requestParam) {
        Map<Integer, Integer> seatTypeCounts = new TreeMap<>();
        for (PurchaseTicketPassengerDetailDTO passenger : requestParam.getPassengers()) {
            seatTypeCounts.merge(passenger.getSeatType(), 1, Integer::sum);
        }
        return seatTypeCounts;
    }

    private void unlockSafely(RLock lock, String lockType) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ex) {
            log.error("{}释放失败，等待 Redisson 租约兜底，lock={}", lockType, lock.getName(), ex);
        }
    }
}
