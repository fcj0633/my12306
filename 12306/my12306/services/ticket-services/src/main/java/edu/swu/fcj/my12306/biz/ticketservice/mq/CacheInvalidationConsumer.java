package edu.swu.fcj.my12306.biz.ticketservice.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.service.cache.InventoryCacheInvalidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name="my12306.cdc.enabled",havingValue="true")
public class CacheInvalidationConsumer implements MessageListenerConcurrently {
    private final ObjectMapper json;
    private final SeatMapper mapper;
    private final InventoryCacheInvalidator invalidator;
    private final MeterRegistry metrics;
    private final String database;
    public CacheInvalidationConsumer(ObjectMapper json, SeatMapper mapper, InventoryCacheInvalidator invalidator,
            MeterRegistry metrics, @Value("${my12306.cdc.database:}") String database) {
        if (!database.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("CDC source database is required");
        this.json=json; this.mapper=mapper; this.invalidator=invalidator; this.metrics=metrics; this.database=database;
    }
    @Override public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        metrics.counter("my12306.cdc.consumer.received").increment(messages.size());
        try {
            for (var message:messages) {
                if (message.getBody()==null || message.getBody().length>256*1024) throw new IllegalArgumentException("Invalid CDC body size");
                process(json.readValue(message.getBody(),InventoryCdcEvent.class));
            }
            metrics.counter("my12306.cdc.consumer.success").increment(messages.size());
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (Exception ex) {
            metrics.counter("my12306.cdc.consumer.retry").increment();
            LoggerFactory.getLogger(getClass()).error("CDC invalidation incomplete; returning RECONSUME_LATER (DLQ after 16 retries)",ex);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }
    private record Coordinate(long seatId,int segment) { }
    static boolean available(Map<String,String> values) {
        return "0".equals(values.get("seat_status")) && values.get("ticket_id")==null && values.get("order_sn")==null;
    }
    private static Coordinate coordinate(Map<String,String> values) {
        for (String field:List.of("seat_id","segment_index","seat_status","ticket_id","order_sn"))
            if (!values.containsKey(field)) throw new IllegalArgumentException("Missing inventory field: "+field);
        int status=Integer.parseInt(values.get("seat_status"));
        long seat=Long.parseLong(values.get("seat_id")); int segment=Integer.parseInt(values.get("segment_index"));
        if (seat<=0 || segment<0 || status<0 || status>2) throw new IllegalArgumentException("Invalid inventory coordinate/status");
        return new Coordinate(seat,segment);
    }
    public void process(InventoryCdcEvent event) {
        if (event.schemaVersion()!=1 || event.eventId()==null || event.eventId().isBlank()
                || event.source()==null || event.source().isBlank() || !database.equals(event.database())
                || !"t_seat_segment".equals(event.table()) || event.binlogFile()==null || event.binlogFile().isBlank()
                || event.binlogOffset()<=0 || event.sourceTimestamp()<=0 || event.publishedAt()<=0
                || event.rows()==null || event.rows().isEmpty() || event.rows().size()>100)
            throw new IllegalArgumentException("Invalid inventory CDC V1 envelope");
        if (!Set.of("INSERT","UPDATE","DELETE").contains(event.operation())) throw new IllegalArgumentException("Invalid CDC operation");
        var affected=new LinkedHashMap<Coordinate,Boolean>();
        for (var row:event.rows()) {
            if (row==null || row.before()==null || row.after()==null) throw new IllegalArgumentException("Missing row images");
            Coordinate before=null,after=null;
            if (!"INSERT".equals(event.operation())) before=coordinate(row.before());
            else if (!row.before().isEmpty()) throw new IllegalArgumentException("INSERT has before image");
            if (!"DELETE".equals(event.operation())) after=coordinate(row.after());
            else if (!row.after().isEmpty()) throw new IllegalArgumentException("DELETE has after image");
            boolean oldAvailable=before!=null && available(row.before()), newAvailable=after!=null && available(row.after());
            if ("UPDATE".equals(event.operation()) && Objects.equals(before,after) && oldAvailable==newAvailable) {
                metrics.counter("my12306.cdc.consumer.skipped").increment(); continue;
            }
            if (before!=null) affected.merge(before,false,Boolean::logicalOr);
            if (after!=null) affected.merge(after,newAvailable && (!oldAvailable || !Objects.equals(before,after)),Boolean::logicalOr);
        }
        if (affected.isEmpty()) return;
        var ids=affected.keySet().stream().map(Coordinate::seatId).distinct().toList();
        Map<Long,Long> trains=new HashMap<>();
        for (var seat:mapper.selectPhysicalSeatTrains(ids)) trains.put(((Number)seat.get("seat_id")).longValue(),((Number)seat.get("train_id")).longValue());
        Map<Long,Set<Integer>> segments=new TreeMap<>(); var released=new HashSet<Long>();
        affected.forEach((position,release) -> {
            Long train=trains.get(position.seatId());
            if (train==null) throw new IllegalArgumentException("Physical seat missing; CDC migration must be drained before static deletion");
            segments.computeIfAbsent(train,id -> new TreeSet<>()).add(position.segment());
            if (release) released.add(train);
        });
        int keys=0;
        for (var train:segments.entrySet()) keys+=invalidator.invalidate(train.getKey(),train.getValue(),released.contains(train.getKey()));
        metrics.counter("my12306.cdc.consumer.affected.keys").increment(keys);
        long now=System.currentTimeMillis();
        metrics.timer("my12306.cdc.consumer.source.age").record(Math.max(0,now-event.sourceTimestamp()),TimeUnit.MILLISECONDS);
        metrics.timer("my12306.cdc.consumer.message.age").record(Math.max(0,now-event.publishedAt()),TimeUnit.MILLISECONDS);
        LoggerFactory.getLogger(getClass()).info("CDC_INVALIDATED operation={} rows={} keys={}",event.operation(),event.rows().size(),keys);
    }
}
