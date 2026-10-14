package edu.swu.fcj.my12306.biz.ticketservice.mq;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.service.cache.InventoryCacheInvalidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheInvalidationConsumerTest {
    final ObjectMapper json=new ObjectMapper(); final SeatMapper seats=mock(SeatMapper.class);
    final InventoryCacheInvalidator invalidator=mock(InventoryCacheInvalidator.class);
    final CacheInvalidationConsumer consumer=new CacheInvalidationConsumer(json,seats,invalidator,new SimpleMeterRegistry(),"fixture");
    Map<String,String> image(int status,int segment) {
        var values=new HashMap<String,String>(); values.put("seat_id","1"); values.put("segment_index",""+segment);
        values.put("seat_status",""+status); values.put("ticket_id",status==0?null:"91"); values.put("order_sn",status==0?null:"order"); return values;
    }
    InventoryCdcEvent update(int before,int after) {
        return new InventoryCdcEvent(1,"event","source","fixture","t_seat_segment","UPDATE","bin.1",99,
                System.currentTimeMillis(),System.currentTimeMillis(),List.of(new InventoryCdcEvent.Row(image(before,1),image(after,1))));
    }
    void mapping(){when(seats.selectPhysicalSeatTrains(anyList())).thenReturn(List.of(Map.of("seat_id",1L,"train_id",2L)));}
    @Test void reserveReleaseAndPayment() {
        mapping(); consumer.process(update(0,1)); verify(invalidator).invalidate(2L,Set.of(1),false);
        consumer.process(update(1,0)); verify(invalidator).invalidate(2L,Set.of(1),true);
        clearInvocations(invalidator,seats); consumer.process(update(1,2)); verifyNoInteractions(invalidator,seats);
    }
    @Test void duplicatesInvalidateAgainSafely() {
        mapping(); consumer.process(update(0,1)); consumer.process(update(0,1));
        verify(invalidator,times(2)).invalidate(2L,Set.of(1),false);
    }
    @Test void redisFailureReturnsRetry() throws Exception {
        mapping(); when(invalidator.invalidate(anyLong(),anySet(),anyBoolean())).thenThrow(new IllegalStateException("Redis unavailable"));
        var message=new MessageExt(); message.setBody(json.writeValueAsBytes(update(0,1)));
        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER,consumer.consumeMessage(List.of(message),null));
    }
    @Test void missingOwnerFieldMustNotLookAvailable() {
        var before=image(0,1); before.remove("ticket_id"); var e=update(0,1);
        var malformed=new InventoryCdcEvent(1,e.eventId(),e.source(),e.database(),e.table(),e.operation(),e.binlogFile(),99,
                e.sourceTimestamp(),e.publishedAt(),List.of(new InventoryCdcEvent.Row(before,image(1,1))));
        assertThrows(IllegalArgumentException.class,()->consumer.process(malformed));
    }
    @Test void disjointSegmentsDoNotInvalidateGap() {
        var pairs=InventoryCacheInvalidator.affectedPairs(5,Set.of(0,3));
        assertEquals(7,pairs.size()); assertFalse(pairs.stream().anyMatch(p->p[0]==1 && p[1]==3));
        var single=InventoryCacheInvalidator.affectedPairs(5,Set.of(1)); assertEquals(6,single.size());
        assertFalse(single.stream().anyMatch(p->p[1]<=1 || p[0]>1));
        assertThrows(IllegalArgumentException.class,()->InventoryCacheInvalidator.affectedPairs(5,Set.of(4)));
    }
}
