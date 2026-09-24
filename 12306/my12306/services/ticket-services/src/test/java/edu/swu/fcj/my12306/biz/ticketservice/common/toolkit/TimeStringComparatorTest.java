package edu.swu.fcj.my12306.biz.ticketservice.common.toolkit;

import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.TicketListDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeStringComparatorTest {

    @Test
    void sortsByDepartureTime() {
        List<TicketListDTO> trainList = new ArrayList<>(List.of(
                TicketListDTO.builder().trainNumber("G2").departureTime("09:56").build(),
                TicketListDTO.builder().trainNumber("G1").departureTime("07:30").build()));
        trainList.sort(new TimeStringComparator());
        assertEquals("G1", trainList.get(0).getTrainNumber());
        assertEquals("G2", trainList.get(1).getTrainNumber());
    }
}
