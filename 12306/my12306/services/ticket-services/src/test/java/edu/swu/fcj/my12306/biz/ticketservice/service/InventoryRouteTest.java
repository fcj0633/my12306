package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.InventoryStationOrder;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainStationDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.*;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.InventoryRouteService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryRouteTest {
    TrainStationDO station(String sequence,String name) {
        var s=new TrainStationDO();s.setSequence(sequence);s.setDeparture(name);return s;
    }
    @Test void sequencesSortNumericallyBeyondNine() {
        var list=new ArrayList<TrainStationDO>();for(int i=12;i>0;i--) list.add(station(""+i,"S"+i));
        var sorted=InventoryStationOrder.validateAndSort(list);
        assertEquals("2",sorted.get(1).getSequence());assertEquals("10",sorted.get(9).getSequence());
    }
    @Test void malformedMissingDuplicateSequencesAndStationNamesAreRejected() {
        for(var list:List.of(List.of(station("1","A"),station("3","C")),
                List.of(station("1","A"),station("1","B")),List.of(station("1","A"),station("x","B")),
                List.of(station("1","A"),station("2","A"))))
            assertThrows(ServiceException.class,() -> InventoryStationOrder.validateAndSort(list));
    }
    @Test void rangeUsesHalfOpenIntervalsAndCachesOnlyValidatedCoordinates() {
        var mapper=mock(SeatMapper.class);var service=new InventoryRouteService(mapper);
        var list=new ArrayList<InventoryStation>();for(int i=0;i<4;i++) {
            var s=new InventoryStation();s.setStationIndex(i);s.setStationName("ABCD".substring(i,i+1));list.add(s);
        }
        when(mapper.selectInventoryStations(1L)).thenReturn(list);
        assertEquals(new SegmentRange(0,2),service.resolve(1L,"A","C"));
        assertFalse(service.resolve(1L,"A","C").overlaps(service.resolve(1L,"C","D")));
        assertThrows(ServiceException.class,() -> service.resolve(1L,"D","A"));
        assertThrows(ServiceException.class,() -> service.resolve(1L,"A","X"));
        verify(mapper,times(1)).selectInventoryStations(1L);
    }
}
