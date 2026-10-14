package edu.swu.fcj.my12306.biz.ticketservice.service.cache;

import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.CacheUtil;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.InventoryRouteService;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.*;

/** One aggregate for the requested OD; all counted seats must be free throughout the journey. */
@Component
@RequiredArgsConstructor
public class SeatMarginCacheLoader {
    private final TrainMapper trainMapper;
    private final SeatMapper seatMapper;
    private final RedisCacheHelper cache;
    private final InventoryRouteService routes;
    private final MeterRegistry metrics;
    @Value("${my12306.inventory.remaining-ttl-seconds:30}") private long ttlSeconds;

    public Map<String,String> load(String trainId,Integer seatType,String departure,String arrival) {
        Long id=Long.valueOf(trainId);routes.resolve(id,departure,arrival);
        String suffix=CacheUtil.buildKey(trainId,departure,arrival);
        String key=TRAIN_STATION_REMAINING_TICKET+suffix,field=seatType.toString();
        Object hit=cache.hGet(key,field);
        if (hit!=null) return Map.of(field,hit.toString());
        Map<String,String> result=new LinkedHashMap<>();
        cache.executeWithLock(String.format(LOCK_SAFE_LOAD_SEAT_MARGIN_GET,suffix),() -> {
            Object second=cache.hGet(key,field);
            if (second!=null) {result.put(field,second.toString());return;}
            for (int attempt=0; attempt<2; attempt++) {
                String version=cache.remainingVersion(key);
                Map<Object,Object> values=cache.diagnosed(key,() -> {
                    var train=cache.train(trainId,trainMapper);
                    if (train==null) throw new IllegalStateException("车次不存在");
                    Map<Object,Object> snapshot=new LinkedHashMap<>();
                    for (Integer type:VehicleTypeEnum.findSeatTypesByCode(train.getTrainType())) snapshot.put(type.toString(),"0");
                    snapshot.putIfAbsent(field,"0");
                    for (var row:seatMapper.countAvailableByType(id,departure,arrival))
                        snapshot.put(TicketAvailabilityTokenBucket.value(row,"seat_type").toString(),TicketAvailabilityTokenBucket.value(row,"cnt").toString());
                    metrics.counter("my12306.inventory.remaining.load").increment();
                    return snapshot;
                });
                result.clear(); values.forEach((k,v)->result.put(k.toString(),v.toString()));
                if(cache.publishRemaining(key,version,values,Math.max(1,ttlSeconds))) return;
                cache.remainingRetry(key,attempt==1);
            }

        });
        return result;
    }
}
