package edu.swu.fcj.my12306.biz.ticketservice.service;

import com.alibaba.fastjson2.JSON;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.*;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.*;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.PurchaseMetadataService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseMetadataServiceTest {
    final TrainMapper trains=mock(TrainMapper.class);
    final TrainStationRelationMapper relations=mock(TrainStationRelationMapper.class);
    final TrainStationPriceMapper prices=mock(TrainStationPriceMapper.class);
    final RedisCacheHelper cache=mock(RedisCacheHelper.class);
    final PurchaseMetadataService service=new PurchaseMetadataService(trains,relations,prices,cache,new SimpleMeterRegistry());
    PurchaseTicketReqDTO request(int... types) {
        var r=new PurchaseTicketReqDTO();r.setTrainId("1");r.setDeparture("北京南");r.setArrival("宁波");
        r.setPassengers(Arrays.stream(types).mapToObj(type->{var p=new PurchaseTicketPassengerDetailDTO();p.setSeatType(type);return p;}).toList());return r;
    }
    TrainStationRelationDO fixture() {
        TrainDO train=new TrainDO();train.setTrainNumber("G1");
        when(cache.safeGet(anyString(), org.mockito.ArgumentMatchers.<Supplier<String>>any(),anyLong(),any(TimeUnit.class))).thenReturn(JSON.toJSONString(train));
        var relation=new TrainStationRelationDO();relation.setDepartureTime(new Date(1000));relation.setArrivalTime(new Date(2000));
        when(relations.selectOne(any())).thenReturn(relation);return relation;
    }
    @Test void sameTypeIsReadOnceAndSnapshotCannotBeMutated() {
        var relation=fixture();var price=new TrainStationPriceDO();price.setPrice(500);when(prices.selectOne(any())).thenReturn(price);
        var metadata=service.prepare(request(2,2,2,2,2));
        verify(prices,times(1)).selectOne(any());assertEquals(500,metadata.amount(2));
        relation.getDepartureTime().setTime(9000);price.setPrice(700);
        assertEquals(1000,metadata.departureTime().toEpochMilli());assertEquals(500,metadata.amount(2));
        assertThrows(UnsupportedOperationException.class,()->metadata.amountsBySeatType().put(2,10));
    }
    @Test void distinctTypesHaveIndependentPrices() {
        fixture();var first=new TrainStationPriceDO();first.setPrice(500);var second=new TrainStationPriceDO();second.setPrice(800);
        when(prices.selectOne(any())).thenReturn(first,second);
        var metadata=service.prepare(request(2,1,2));verify(prices,times(2)).selectOne(any());
        assertEquals(500,metadata.amount(2));assertEquals(800,metadata.amount(1));
    }
    @Test void missingTrainFailsBeforeOtherReads() {
        assertThrows(ServiceException.class,()->service.prepare(request(2)));verifyNoInteractions(relations,prices);
    }
    @Test void missingRelationFailsBeforePrices() {
        TrainDO train=new TrainDO();train.setTrainNumber("G1");when(cache.safeGet(anyString(),org.mockito.ArgumentMatchers.<Supplier<String>>any(),anyLong(),any(TimeUnit.class))).thenReturn(JSON.toJSONString(train));
        assertThrows(ServiceException.class,()->service.prepare(request(2)));verifyNoInteractions(prices);
    }
    @Test void missingPricePreservesBusinessError() {
        fixture();assertEquals("车票价格数据缺失",assertThrows(ServiceException.class,()->service.prepare(request(2))).getMessage());
    }
}
