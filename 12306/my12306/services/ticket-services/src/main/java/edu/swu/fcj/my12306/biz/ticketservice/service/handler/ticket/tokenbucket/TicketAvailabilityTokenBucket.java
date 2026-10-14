package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket;

import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.SegmentRange;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.InventoryRouteService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.*;

/** Segment capacity admission is a hint; only the database proves a continuous physical seat exists. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketAvailabilityTokenBucket {
    private static final DefaultRedisScript<Long> TAKE=new DefaultRedisScript<>();
    static { TAKE.setLocation(new ClassPathResource("lua/take_token_from_bucket.lua")); TAKE.setResultType(Long.class); }
    private final StringRedisTemplate stringRedisTemplate;
    private final SeatMapper seatMapper;
    private final TrainMapper trainMapper;
    private final RedisCacheHelper redisCacheHelper;
    private final MeterRegistry meterRegistry;
    private final InventoryRouteService routes;
    @Value("${my12306.availability.token-bucket.enabled:true}") private boolean enabled;
    @Value("${my12306.availability.token-bucket.expire-minutes:10}") private long expireMinutes;

    public boolean takeToken(Long trainId,String departure,String arrival,Map<Integer,Integer> counts) {
        if (!enabled || counts == null || counts.isEmpty()) return true;
        var range=routes.resolve(trainId,departure,arrival);
        var requested=fields(range,counts);
        if (requested.isEmpty()) return true;
        try {
            Long result=execute(trainId,requested);
            if (Long.valueOf(-1).equals(result)) {
                load(trainId,requested.keySet().stream().map(x -> (Object)x).toList());
                result=execute(trainId,requested);
            }
            if (Long.valueOf(1).equals(result)) { meterRegistry.counter("my12306.token.pass").increment();return true; }
            if (Long.valueOf(0).equals(result)) { meterRegistry.counter("my12306.token.reject").increment();return false; }
            meterRegistry.counter("my12306.token.degrade").increment();return true;
        } catch (Exception ex) {
            log.warn("区段令牌异常，降级到数据库占座。trainId={}",trainId,ex);
            meterRegistry.counter("my12306.token.degrade").increment();return true;
        }
    }

    // Compatibility for orchestration: no increments, no duplicate returns, no resurrection of expired buckets.
    public void returnToken(Long trainId,String departure,String arrival,Map<Integer,Integer> counts) {
        if (!enabled || counts == null || counts.isEmpty()) return;
        try { invalidateOrThrow(trainId); }
        catch (Exception ex) { meterRegistry.counter("my12306.token.degrade").increment();log.warn("令牌桶失效失败，等待 TTL 自愈",ex); }
    }

    /** Outbox uses the throwing variant so a Redis failure leaves the durable event pending. */
    public void invalidateOrThrow(Long trainId) { redisCacheHelper.delete(bucketKey(trainId)); }
    public static String bucketKey(Long trainId) { return TRAIN_STATION_TOKEN_BUCKET+"segments:v1:"+trainId; }

    private void load(Long trainId,List<Object> requestedFields) {
        redisCacheHelper.executeWithLock(String.format(LOCK_TRAIN_STATION_TOKEN_BUCKET,"segments:v1:"+trainId),() -> {
            if (RedisCacheHelper.allNonNull(redisCacheHelper.hMultiGet(bucketKey(trainId),requestedFields))) return;
            var train=trainMapper.selectById(trainId);
            if (train == null) throw new IllegalStateException("车次不存在");
            var types=VehicleTypeEnum.findSeatTypesByCode(train.getTrainType());
            if (types.isEmpty()) throw new IllegalStateException("车型没有席别");
            int segmentCount=routes.stations(trainId).size()-1;
            Map<Object,Object> bucket=new LinkedHashMap<>();
            for (int i=0;i<segmentCount;i++) for (int type:types) bucket.put(i+"_"+type,"0");
            for (var row:seatMapper.countAvailableSegments(trainId)) {
                String field=value(row,"segment_index")+"_"+value(row,"seat_type");
                if (bucket.containsKey(field)) bucket.put(field,value(row,"cnt").toString());
            }
            redisCacheHelper.hPutAllWithTtl(bucketKey(trainId),bucket,Math.max(1,expireMinutes),TimeUnit.MINUTES);
            meterRegistry.counter("my12306.token.load").increment();
        });
    }

    private Map<String,Integer> fields(SegmentRange range,Map<Integer,Integer> counts) {
        Map<String,Integer> result=new TreeMap<>();
        counts.forEach((type,count) -> { if (type!=null && count!=null && count>0)
            for (int i=range.start();i<range.end();i++) result.put(i+"_"+type,count); });
        return result;
    }
    private Long execute(Long trainId,Map<String,Integer> counts) {
        List<String> args=new ArrayList<>();counts.forEach((field,count) -> {args.add(field);args.add(count.toString());});
        return meterRegistry.timer("my12306.token.lua").record(() -> stringRedisTemplate.execute(TAKE,List.of(bucketKey(trainId)),args.toArray()));
    }
    public static Object value(Map<String,Object> row,String key) {
        return row.entrySet().stream().filter(x -> x.getKey().equalsIgnoreCase(key)).findFirst().orElseThrow().getValue();
    }
}
