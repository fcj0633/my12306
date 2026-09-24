package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.SeatStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通用座位分配器：优先同车厢（按座位号排序取连续段），不足时降级为任意可售座位
 */
@Component
@RequiredArgsConstructor
public class SeatAllocator {

    private final SeatMapper seatMapper;

    public List<SeatDO> allocate(Long trainId, String departure, String arrival, Integer seatType, int count) {
        List<SeatDO> availableSeats = seatMapper.selectList(Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, trainId)
                .eq(SeatDO::getSeatType, seatType)
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                .eq(SeatDO::getStartStation, departure)
                .eq(SeatDO::getEndStation, arrival)
                .orderByAsc(SeatDO::getCarriageNumber)
                .orderByAsc(SeatDO::getSeatNumber));
        if (availableSeats.size() < count) {
            return List.of();
        }
        Map<String, List<SeatDO>> seatsByCarriage = availableSeats.stream()
                .collect(Collectors.groupingBy(SeatDO::getCarriageNumber, LinkedHashMap::new, Collectors.toList()));
        for (List<SeatDO> carriageSeats : seatsByCarriage.values()) {
            if (carriageSeats.size() >= count) {
                return new ArrayList<>(carriageSeats.subList(0, count));
            }
        }
        return new ArrayList<>(availableSeats.subList(0, count));
    }
}
