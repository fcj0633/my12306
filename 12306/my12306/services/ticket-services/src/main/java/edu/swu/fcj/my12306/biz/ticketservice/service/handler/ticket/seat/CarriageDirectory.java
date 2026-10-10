package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat;

import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Static carriage coordinates only; availability is always checked inside the reservation transaction. */
@Component
public class CarriageDirectory {
    private final SeatMapper mapper;
    private final long workerId;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, AtomicLong> rotations = new ConcurrentHashMap<>();

    public CarriageDirectory(SeatMapper mapper, @Value("${my12306.snowflake.worker-id}") long workerId) {
        this.mapper = mapper;
        this.workerId = workerId;
    }

    public List<String> carriages(Long trainId, Integer seatType) {
        Key key = new Key(trainId, seatType);
        return entries.compute(key, (ignored, previous) -> {
            long now = System.nanoTime();
            if (previous != null && now - previous.loadedAt < TimeUnit.MINUTES.toNanos(5)) return previous;
            List<String> values = mapper.selectCarriageNumbers(trainId, seatType).stream()
                    .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
            return new Entry(values, System.nanoTime());
        }).values;
    }

    public int nextStart(Long trainId, Integer seatType, int size) {
        if (size <= 0) throw new IllegalArgumentException("车厢目录不能为空");
        long sequence = rotations.computeIfAbsent(new Key(trainId, seatType),
                ignored -> new AtomicLong(workerId)).getAndIncrement();
        return (int) Math.floorMod(sequence, (long) size);
    }

    private record Key(Long trainId, Integer seatType) { }
    private record Entry(List<String> values, long loadedAt) { }
}
