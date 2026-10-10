package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat.SeatAllocator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeatAllocatorTest {
    private final SeatMapper mapper = mock(SeatMapper.class);
    private final SeatAllocator allocator = new SeatAllocator(mapper);
    private final List<SeatDO> available = new ArrayList<>();
    private int queries;

    private void fixture(int... perCarriage) {
        available.clear();
        long id = 1;
        for (int c = 0; c < perCarriage.length; c++) {
            for (int i = 0; i < perCarriage[c]; i++) {
                SeatDO seat = new SeatDO();
                seat.setId(id++);
                seat.setCarriageNumber(String.format("%02d", c + 1));
                seat.setSeatNumber(String.format("%02dA", i + 1));
                available.add(seat);
            }
        }
        queries = 0;
        when(mapper.selectAvailableSeatCandidates(anyLong(), anyString(), anyString(), anyInt(),
                nullable(String.class), anyInt())).thenAnswer(call -> {
            queries++;
            String after = call.getArgument(4);
            int n = call.getArgument(5);
            return available.stream().filter(s -> after == null || s.getCarriageNumber().compareTo(after) > 0)
                    .limit(n).toList();
        });
        when(mapper.selectAvailableSeatsInCarriage(anyLong(), anyString(), anyString(), anyInt(),
                anyString(), anyInt())).thenAnswer(call -> {
            queries++;
            String carriage = call.getArgument(4);
            int n = call.getArgument(5);
            return available.stream().filter(s -> s.getCarriageNumber().equals(carriage)).limit(n).toList();
        });
    }

    private List<Long> allocate(int n) {
        return allocator.allocate(1L, "北京南", "宁波", 2, n).stream().map(SeatDO::getId).toList();
    }

    private List<Long> legacy(int n) {
        if (available.size() < n) return List.of();
        var groups = available.stream().sorted(Comparator.comparing(SeatDO::getCarriageNumber)
                        .thenComparing(SeatDO::getSeatNumber))
                .collect(Collectors.groupingBy(SeatDO::getCarriageNumber, LinkedHashMap::new, Collectors.toList()));
        for (var seats : groups.values()) {
            if (seats.size() >= n) return seats.subList(0, n).stream().map(SeatDO::getId).toList();
        }
        return available.subList(0, n).stream().map(SeatDO::getId).toList();
    }

    @Test void singlePassenger_queriesOnce() {
        fixture(90, 90, 90);
        assertEquals(List.of(1L), allocate(1));
        assertEquals(1, queries);
        verify(mapper).selectAvailableSeatCandidates(1L, "北京南", "宁波", 2, null, 1);
        verifyNoMoreInteractions(mapper);
    }

    @Test void soldOut_queriesOnce() {
        fixture(0);
        assertEquals(List.of(), allocate(1));
        assertEquals(1, queries);
    }

    @Test void firstCarriageFits_queriesOnce() {
        fixture(5, 5);
        assertEquals(legacy(5), allocate(5));
        assertEquals(1, queries);
    }

    @Test void laterCarriageFits_keepsSameCarriagePreference() {
        fixture(2, 6);
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L), allocate(5));
        assertEquals(2, queries);
    }

    @Test void multipleSmallCarriages_seeksPastCompletedCarriages() {
        fixture(2, 2, 2, 2, 6);
        assertEquals(legacy(5), allocate(5));
        assertTrue(queries > 2);
    }

    @Test void onlyCrossCarriageFits_usesOriginalPrefix() {
        fixture(2, 2, 2);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), allocate(5));
    }

    @Test void totalShortage_returnsNothing() {
        fixture(2, 2);
        assertEquals(List.of(), allocate(5));
        assertEquals(1, queries);
    }

    @Test void invalidCount_neverQueries() {
        assertThrows(IllegalArgumentException.class, () -> allocate(0));
        assertThrows(IllegalArgumentException.class, () -> allocate(-1));
        verifyNoInteractions(mapper);
    }

    @Test void randomizedFragmentation_matchesLegacySelection() {
        Random random = new Random(810);
        for (int trial = 0; trial < 300; trial++) {
            int[] counts = new int[1 + random.nextInt(9)];
            for (int i = 0; i < counts.length; i++) counts[i] = random.nextInt(13);
            fixture(counts);
            for (int n = 1; n <= 8; n++) assertEquals(legacy(n), allocate(n), "trial=" + trial + ", n=" + n);
        }
    }

    @Test void carriageSelectionHandlesRandomFragmentationWithoutPartialResults() {
        Random random = new Random(20261009);
        for (int trial = 0; trial < 200; trial++) {
            int[] counts = {random.nextInt(8), random.nextInt(8), random.nextInt(8)};
            fixture(counts);
            for (int c = 0; c < counts.length; c++) {
                String carriage = String.format("%02d", c + 1);
                for (int n = 1; n <= 5; n++) {
                    List<SeatDO> result = allocator.allocateInCarriage(1L,"北京南","宁波",2,carriage,n);
                    assertEquals(counts[c] >= n ? n : 0, result.size());
                    assertTrue(result.stream().allMatch(seat -> carriage.equals(seat.getCarriageNumber())));
                    assertEquals(result.size(), result.stream().map(SeatDO::getId).distinct().count());
                }
            }
        }
    }
}
