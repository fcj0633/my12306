package edu.swu.fcj.my12306.biz.ticketservice.dto.domain;

import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import java.time.Instant;
import java.util.Map;

/** Request-scoped metadata snapshot. No seat availability is cached here. */
public record PurchaseMetadata(String trainNumber, Instant departureTime, Instant arrivalTime,
                               Map<Integer, Integer> amountsBySeatType) {
    public PurchaseMetadata {
        amountsBySeatType = Map.copyOf(amountsBySeatType);
    }

    public Integer amount(Integer seatType) {
        Integer amount = amountsBySeatType.get(seatType);
        if (amount == null) throw new ServiceException("车票价格数据缺失");
        return amount;
    }
}
