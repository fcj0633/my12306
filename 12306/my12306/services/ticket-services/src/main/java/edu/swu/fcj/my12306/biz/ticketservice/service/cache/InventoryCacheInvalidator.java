package edu.swu.fcj.my12306.biz.ticketservice.service.cache;

import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.CacheUtil;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.InventoryRouteService;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/** Both the transactional outbox and CDC use the same exact segment-to-OD mapping. */
@Service
@RequiredArgsConstructor
public class InventoryCacheInvalidator {
    private final RedisCacheHelper cache;
    private final InventoryRouteService routes;
    private final TicketAvailabilityTokenBucket bucket;
    private final MeterRegistry metrics;
    public static List<int[]> affectedPairs(int stationCount, Set<Integer> segments) {
        if (stationCount<2 || segments.isEmpty() || segments.stream().anyMatch(s -> s==null || s<0 || s>=stationCount-1))
            throw new IllegalArgumentException("Invalid inventory segment indices");
        var pairs=new ArrayList<int[]>();
        for (int i=0;i<stationCount-1;i++) for (int j=i+1;j<stationCount;j++) {
            final int start=i,end=j;
            if (segments.stream().anyMatch(s -> start<=s && s<end)) pairs.add(new int[]{i,j});
        }
        return pairs;
    }
    public int invalidate(Long trainId, Set<Integer> segments, boolean released) {
        long started=System.nanoTime();
        try {
            var stations=routes.stations(trainId);
            var keys=affectedPairs(stations.size(),segments).stream().map(pair -> TRAIN_STATION_REMAINING_TICKET+
                    CacheUtil.buildKey(trainId.toString(),stations.get(pair[0]).getStationName(),stations.get(pair[1]).getStationName())).toList();
            cache.invalidateRemaining(keys); // Each attempt creates fresh random versions, including duplicates.
            if (released) bucket.invalidateOrThrow(trainId);
            metrics.counter("my12306.inventory.invalidation.keys").increment(keys.size());
            return keys.size();
        } finally { metrics.timer("my12306.inventory.invalidation.duration").record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS); }
    }
}
