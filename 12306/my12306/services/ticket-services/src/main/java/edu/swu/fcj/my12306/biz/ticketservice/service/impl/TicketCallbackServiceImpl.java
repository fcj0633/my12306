package edu.swu.fcj.my12306.biz.ticketservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.SeatStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.TicketStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.CacheUtil;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TicketDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TicketMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.TicketCallbackSeatDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketCallbackReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.TicketCallbackService;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 票务回调实现
 * <p>
 * 幂等的实现方式统一为"条件更新 + 影响行数"：
 * <ul>
 *   <li>车票先以 orderSn 从"未支付"推进，只有取得推进权的回调才能修改座位；</li>
 *   <li>座位只在"已锁定"时才能被推进，失败时回滚本次车票状态变更；</li>
 *   <li>影响行数为 0 不抛异常 —— 这在跨服务重试里是"已经处理过"的正常语义。</li>
 * </ul>
 * 之所以不用"先查再改"，是因为查询与修改之间存在并发窗口：两个请求可能都查到"已锁定"，
 * 然后先后都去改，把状态改错。把判断条件写进 WHERE，判断与修改就在数据库里原子完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketCallbackServiceImpl implements TicketCallbackService {

    private final SeatMapper seatMapper;

    private final TicketMapper ticketMapper;

    private final RedisCacheHelper redisCacheHelper;

    private final TicketAvailabilityTokenBucket tokenBucket;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean payCallback(TicketCallbackReqDTO requestParam) {
        checkParam(requestParam);
        for (TicketCallbackSeatDTO seat : requestParam.getSeats()) {
            int ticketAffectedRows = updateTicketStatus(requestParam, seat, TicketStatusEnum.PAID.getCode());
            if (ticketAffectedRows == 0) {
                continue;
            }
            // The order-owned ticket wins the state transition first. This prevents a late callback
            // from an old order from advancing a seat that has since been reserved by another order.
            int affectedRows = seatMapper.update(buildSeatUpdate(SeatStatusEnum.SOLD.getCode()),
                    buildSeatCondition(requestParam, seat)
                            .eq(SeatDO::getSeatStatus, SeatStatusEnum.LOCKED.getCode()));
            if (affectedRows == 0) {
                restoreUnpaidTicketStatus(requestParam, seat, TicketStatusEnum.PAID.getCode());
                log.warn("支付回调未推进座位状态，已恢复车票状态。orderSn={}，车厢={}，座号={}",
                        requestParam.getOrderSn(), seat.getCarriageNumber(), seat.getSeatNumber());
            }
        }
        // 座位状态变了，余票缓存立刻失效，让下次查询回源重算（否则会出现"已售完但还能查到有票"）
        evictRemainingTicketCache(requestParam);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean cancelCallback(TicketCallbackReqDTO requestParam) {
        checkParam(requestParam);
        Map<Integer, Integer> releasedBySeatType = new HashMap<>();
        for (TicketCallbackSeatDTO seat : requestParam.getSeats()) {
            int ticketAffectedRows = updateTicketStatus(requestParam, seat, TicketStatusEnum.CANCELED.getCode());
            if (ticketAffectedRows == 0) {
                continue;
            }
            // Claim the old order's unpaid ticket before releasing its seat. A duplicate callback
            // can no longer release the same coordinates after another order reserves them again.
            int affectedRows = seatMapper.update(buildSeatUpdate(SeatStatusEnum.AVAILABLE.getCode()),
                    buildSeatCondition(requestParam, seat)
                            .eq(SeatDO::getSeatStatus, SeatStatusEnum.LOCKED.getCode()));
            if (affectedRows > 0) {
                releasedBySeatType.merge(seat.getSeatType(), affectedRows, Integer::sum);
            } else {
                restoreUnpaidTicketStatus(requestParam, seat, TicketStatusEnum.CANCELED.getCode());
                log.warn("取消回调未回滚座位，已恢复车票状态。orderSn={}，车厢={}，座号={}",
                        requestParam.getOrderSn(), seat.getCarriageNumber(), seat.getSeatNumber());
            }
        }
        if (!releasedBySeatType.isEmpty()) {
            returnTokensAfterCommit(requestParam, releasedBySeatType);
        }
        evictRemainingTicketCache(requestParam);
        return Boolean.TRUE;
    }

    /**
     * 数据库提交后再归还令牌，避免提交失败时 Redis 已提前增加可售准入量。
     */
    private void returnTokensAfterCommit(TicketCallbackReqDTO requestParam,
                                         Map<Integer, Integer> releasedBySeatType) {
        Map<Integer, Integer> tokens = Map.copyOf(releasedBySeatType);
        Runnable returnAction = () -> tokenBucket.returnToken(requestParam.getTrainId(), requestParam.getDeparture(),
                requestParam.getArrival(), tokens);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            returnAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                returnAction.run();
            }
        });
    }

    private void checkParam(TicketCallbackReqDTO requestParam) {
        if (requestParam == null || requestParam.getTrainId() == null
                || StrUtil.isBlank(requestParam.getDeparture()) || StrUtil.isBlank(requestParam.getArrival())) {
            throw new ServiceException("回调参数不完整");
        }
        if (CollUtil.isEmpty(requestParam.getSeats())) {
            throw new ServiceException("回调座位列表不能为空");
        }
    }

    private SeatDO buildSeatUpdate(Integer seatStatus) {
        SeatDO updateSeat = new SeatDO();
        updateSeat.setSeatStatus(seatStatus);
        return updateSeat;
    }

    private LambdaUpdateWrapper<SeatDO> buildSeatCondition(
            TicketCallbackReqDTO requestParam, TicketCallbackSeatDTO seat) {
        return Wrappers.lambdaUpdate(SeatDO.class)
                .eq(SeatDO::getTrainId, requestParam.getTrainId())
                .eq(SeatDO::getStartStation, requestParam.getDeparture())
                .eq(SeatDO::getEndStation, requestParam.getArrival())
                .eq(SeatDO::getSeatType, seat.getSeatType())
                .eq(SeatDO::getCarriageNumber, seat.getCarriageNumber())
                .eq(SeatDO::getSeatNumber, seat.getSeatNumber());
    }

    /**
     * 车票状态推进：只推进"未支付"的记录，重复回调影响 0 行
     */
    private int updateTicketStatus(TicketCallbackReqDTO requestParam, TicketCallbackSeatDTO seat, Integer targetStatus) {
        TicketDO updateTicket = new TicketDO();
        updateTicket.setTicketStatus(targetStatus);
        updateTicket.setUpdateTime(new Date());
        int affectedRows = ticketMapper.update(updateTicket, Wrappers.lambdaUpdate(TicketDO.class)
                .eq(TicketDO::getOrderSn, requestParam.getOrderSn())
                .eq(TicketDO::getTrainId, requestParam.getTrainId())
                .eq(TicketDO::getStartStation, requestParam.getDeparture())
                .eq(TicketDO::getEndStation, requestParam.getArrival())
                .eq(TicketDO::getSeatType, seat.getSeatType())
                .eq(TicketDO::getCarriageNumber, seat.getCarriageNumber())
                .eq(TicketDO::getSeatNumber, seat.getSeatNumber())
                .eq(TicketDO::getTicketStatus, TicketStatusEnum.UNPAID.getCode()));
        if (affectedRows == 0) {
            log.warn("回调未推进车票状态（可能已处理），orderSn={}，车厢={}，座号={}",
                    requestParam.getOrderSn(), seat.getCarriageNumber(), seat.getSeatNumber());
        }
        return affectedRows;
    }

    /**
     * A callback claims the order-owned ticket before touching the coordinate-only seat row. If the
     * seat cannot move, restore the ticket while its row lock is still held so the callback remains
     * a no-op and no later retry can mistake a partial transition for success.
     */
    private void restoreUnpaidTicketStatus(
            TicketCallbackReqDTO requestParam, TicketCallbackSeatDTO seat, Integer claimedStatus) {
        TicketDO updateTicket = new TicketDO();
        updateTicket.setTicketStatus(TicketStatusEnum.UNPAID.getCode());
        updateTicket.setUpdateTime(new Date());
        int affectedRows = ticketMapper.update(updateTicket, Wrappers.lambdaUpdate(TicketDO.class)
                .eq(TicketDO::getOrderSn, requestParam.getOrderSn())
                .eq(TicketDO::getTrainId, requestParam.getTrainId())
                .eq(TicketDO::getStartStation, requestParam.getDeparture())
                .eq(TicketDO::getEndStation, requestParam.getArrival())
                .eq(TicketDO::getSeatType, seat.getSeatType())
                .eq(TicketDO::getCarriageNumber, seat.getCarriageNumber())
                .eq(TicketDO::getSeatNumber, seat.getSeatNumber())
                .eq(TicketDO::getTicketStatus, claimedStatus));
        if (affectedRows != 1) {
            throw new ServiceException("回调车票状态恢复失败");
        }
    }

    private void evictRemainingTicketCache(TicketCallbackReqDTO requestParam) {
        String cacheKey = TRAIN_STATION_REMAINING_TICKET
                + CacheUtil.buildKey(String.valueOf(requestParam.getTrainId()),
                requestParam.getDeparture(), requestParam.getArrival());
        redisCacheHelper.delete(cacheKey);
    }
}
