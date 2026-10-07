package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat;

import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 优先能容纳全部乘车人的首个车厢，否则取全局排序前 N 张；不保证物理连座。
 * 每次只读取至多 N 张候选，配合有序索引避免全量可售座位物化。
 */
@Component
@RequiredArgsConstructor
public class SeatAllocator {

    private final SeatMapper seatMapper;

    public List<SeatDO> allocate(Long trainId, String departure, String arrival, Integer seatType, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("购票人数必须大于零");
        }
        List<SeatDO> candidates = seatMapper.selectAvailableSeatCandidates(
                trainId, departure, arrival, seatType, null, count);
        if (candidates.size() < count) {
            return List.of();
        }
        List<SeatDO> fallback = List.copyOf(candidates);
        while (true) {
            String firstCarriage = candidates.getFirst().getCarriageNumber();
            String lastCarriage = candidates.getLast().getCarriageNumber();
            if (Objects.equals(firstCarriage, lastCarriage)) {
                return candidates;
            }
            // A boundary in the sorted prefix proves that all earlier carriages have < N seats.
            // Only the last carriage can extend beyond this prefix and still contain N seats.
            List<SeatDO> inCarriage = seatMapper.selectAvailableSeatsInCarriage(
                    trainId, departure, arrival, seatType, lastCarriage, count);
            if (inCarriage.size() == count) {
                return inCarriage;
            }
            candidates = seatMapper.selectAvailableSeatCandidates(
                    trainId, departure, arrival, seatType, lastCarriage, count);
            if (candidates.size() < count) {
                return fallback;
            }
        }
    }
}
