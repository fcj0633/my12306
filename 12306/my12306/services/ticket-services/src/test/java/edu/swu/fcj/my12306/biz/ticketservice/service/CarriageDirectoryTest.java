package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat.CarriageDirectory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarriageDirectoryTest {
    @Test void directoryCachesOnlySortedCoordinatesAndRotationIsThreadSafe() {
        SeatMapper mapper = mock(SeatMapper.class);
        when(mapper.selectCarriageNumbers(1L, 2)).thenReturn(List.of("09", "07", "08", "07"));
        CarriageDirectory directory = new CarriageDirectory(mapper, 1);
        assertEquals(List.of("07", "08", "09"), directory.carriages(1L, 2));
        assertEquals(List.of("07", "08", "09"), directory.carriages(1L, 2));
        verify(mapper).selectCarriageNumbers(1L, 2);
        ConcurrentHashMap<Integer, Long> counts = new ConcurrentHashMap<>();
        IntStream.range(0, 900).parallel().forEach(i -> counts.merge(directory.nextStart(1L, 2, 3), 1L, Long::sum));
        assertEquals(300L, counts.get(0));
        assertEquals(300L, counts.get(1));
        assertEquals(300L, counts.get(2));
    }
}
