package edu.swu.fcj.my12306.biz.ticketservice.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.SeatStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.SourceEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.TicketStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TicketDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainStationRelationDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TicketMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainStationRelationMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseReservationResult;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.TicketCallbackSeatDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketCallbackReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketOrderDetailRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.TicketOrderItemCreateRemoteReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat.SeatAllocator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;

/**
 * 购票本地事务层。调用方已经完成乘车人校验并持有用户锁和全部席别锁。
 * 本事务只操作 ticket 数据库；远程调用留在编排层，避免网络等待延长行锁持有时间。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseTicketTxService {

    private static final String SEAT_NOT_ENOUGH_MESSAGE = "站点余票不足，请尝试更换座位类型或选择其它站点";

    private final TrainMapper trainMapper;
    private final TrainStationRelationMapper trainStationRelationMapper;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final SeatMapper seatMapper;
    private final TicketMapper ticketMapper;
    private final RedisCacheHelper redisCacheHelper;
    private final SeatAllocator seatAllocator;

    /**
     * 在锁内原子完成选座、条件占座和车票写入；必须由外层 Bean 调用以经过 Spring 事务代理。
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseReservationResult doPurchaseInTransaction(
            PurchaseTicketReqDTO requestParam, String userId, String username, String orderSn,
            Map<String, PassengerActualRespDTO> passengersById) {
        TrainDO trainDO = loadTrain(requestParam.getTrainId());
        if (trainDO == null) {
            throw new ServiceException("请检查车次是否存在");
        }
        TrainStationRelationDO relation = trainStationRelationMapper.selectOne(
                Wrappers.lambdaQuery(TrainStationRelationDO.class)
                        .eq(TrainStationRelationDO::getTrainId, Long.valueOf(requestParam.getTrainId()))
                        .eq(TrainStationRelationDO::getDeparture, requestParam.getDeparture())
                        .eq(TrainStationRelationDO::getArrival, requestParam.getArrival()));
        if (relation == null) {
            throw new ServiceException("列车车站数据错误");
        }

        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType,
                        LinkedHashMap::new, Collectors.toList()));
        List<SeatAssignResult> assignResults = new ArrayList<>();
        for (Map.Entry<Integer, List<PurchaseTicketPassengerDetailDTO>> entry : seatTypeMap.entrySet()) {
            List<PurchaseTicketPassengerDetailDTO> passengers = entry.getValue();
            List<SeatDO> seats = seatAllocator.allocate(Long.valueOf(requestParam.getTrainId()),
                    requestParam.getDeparture(), requestParam.getArrival(), entry.getKey(), passengers.size());
            if (seats.size() < passengers.size()) {
                throw new ServiceException(SEAT_NOT_ENOUGH_MESSAGE);
            }
            List<Long> seatIds = seats.stream().map(SeatDO::getId).toList();
            SeatDO updateSeat = new SeatDO();
            updateSeat.setSeatStatus(SeatStatusEnum.LOCKED.getCode());
            int affectedRows = seatMapper.update(updateSeat, Wrappers.lambdaUpdate(SeatDO.class)
                    .in(SeatDO::getId, seatIds)
                    .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode()));
            if (affectedRows != seats.size()) {
                throw new ServiceException(SEAT_NOT_ENOUGH_MESSAGE);
            }
            for (int index = 0; index < passengers.size(); index++) {
                assignResults.add(new SeatAssignResult(passengers.get(index), seats.get(index)));
            }
        }

        Date now = new Date();
        List<TicketOrderDetailRespDTO> ticketOrderDetails = new ArrayList<>();
        List<TicketOrderItemCreateRemoteReqDTO> orderItems = new ArrayList<>();
        for (SeatAssignResult each : assignResults) {
            PassengerActualRespDTO passenger = passengersById.get(each.passenger().getPassengerId());
            if (passenger == null) {
                throw new ServiceException("乘车人不存在或不属于当前用户");
            }
            Integer amount = queryTicketAmount(requestParam, each.passenger().getSeatType());
            TicketDO ticketDO = TicketDO.builder()
                    .orderSn(orderSn)
                    .username(username)
                    .trainId(Long.valueOf(requestParam.getTrainId()))
                    .carriageNumber(each.seat().getCarriageNumber())
                    .seatNumber(each.seat().getSeatNumber())
                    .passengerId(passenger.getId())
                    .seatType(each.passenger().getSeatType())
                    .startStation(requestParam.getDeparture())
                    .endStation(requestParam.getArrival())
                    .ticketStatus(TicketStatusEnum.UNPAID.getCode())
                    .build();
            ticketDO.setCreateTime(now);
            ticketDO.setUpdateTime(now);
            ticketDO.setDelFlag(0);
            ticketMapper.insert(ticketDO);

            orderItems.add(TicketOrderItemCreateRemoteReqDTO.builder()
                    .amount(amount).carriageNumber(each.seat().getCarriageNumber())
                    .seatNumber(each.seat().getSeatNumber()).realName(passenger.getRealName())
                    .idType(passenger.getIdType()).idCard(passenger.getIdCard())
                    .phone(passenger.getPhone()).seatType(each.passenger().getSeatType())
                    .ticketType(passenger.getDiscountType()).build());
            ticketOrderDetails.add(TicketOrderDetailRespDTO.builder()
                    .amount(amount).carriageNumber(each.seat().getCarriageNumber())
                    .seatNumber(each.seat().getSeatNumber()).realName(passenger.getRealName())
                    .idType(passenger.getIdType()).idCard(passenger.getIdCard())
                    .phone(passenger.getPhone()).seatType(each.passenger().getSeatType())
                    .ticketType(passenger.getDiscountType()).build());
        }

        TicketOrderCreateRemoteReqDTO orderCreateReqDTO = TicketOrderCreateRemoteReqDTO.builder()
                .orderSn(orderSn).userId(userId).username(username).trainId(Long.valueOf(requestParam.getTrainId()))
                .departure(requestParam.getDeparture()).arrival(requestParam.getArrival())
                .source(SourceEnum.INTERNET.getCode()).orderTime(now)
                .ridingDate(relation.getDepartureTime()).trainNumber(trainDO.getTrainNumber())
                .departureTime(relation.getDepartureTime()).arrivalTime(relation.getArrivalTime())
                .ticketOrderItems(orderItems).build();
        List<TicketCallbackSeatDTO> callbackSeats = assignResults.stream().map(each -> {
            TicketCallbackSeatDTO seat = new TicketCallbackSeatDTO();
            seat.setCarriageNumber(each.seat().getCarriageNumber());
            seat.setSeatNumber(each.seat().getSeatNumber());
            seat.setSeatType(each.passenger().getSeatType());
            return seat;
        }).toList();
        TicketCallbackReqDTO compensationRequest = new TicketCallbackReqDTO();
        compensationRequest.setOrderSn(orderSn);
        compensationRequest.setTrainId(Long.valueOf(requestParam.getTrainId()));
        compensationRequest.setDeparture(requestParam.getDeparture());
        compensationRequest.setArrival(requestParam.getArrival());
        compensationRequest.setSeats(callbackSeats);
        return new PurchaseReservationResult(orderCreateReqDTO, ticketOrderDetails, compensationRequest);
    }

    private TrainDO loadTrain(String trainId) {
        String cacheValue = redisCacheHelper.safeGet(TRAIN_INFO + trainId, () -> {
            TrainDO trainDO = trainMapper.selectById(Long.valueOf(trainId));
            return trainDO == null ? null : JSON.toJSONString(trainDO);
        }, ADVANCE_TICKET_DAY, TimeUnit.DAYS);
        return StrUtil.isBlank(cacheValue) ? null : JSON.parseObject(cacheValue, TrainDO.class);
    }

    private Integer queryTicketAmount(PurchaseTicketReqDTO requestParam, Integer seatType) {
        TrainStationPriceDO priceDO = trainStationPriceMapper.selectOne(
                Wrappers.lambdaQuery(TrainStationPriceDO.class)
                        .eq(TrainStationPriceDO::getTrainId, Long.valueOf(requestParam.getTrainId()))
                        .eq(TrainStationPriceDO::getDeparture, requestParam.getDeparture())
                        .eq(TrainStationPriceDO::getArrival, requestParam.getArrival())
                        .eq(TrainStationPriceDO::getSeatType, seatType));
        if (priceDO == null || priceDO.getPrice() == null) {
            throw new ServiceException("车票价格数据缺失");
        }
        return priceDO.getPrice();
    }

    private record SeatAssignResult(PurchaseTicketPassengerDetailDTO passenger, SeatDO seat) {
    }
}
