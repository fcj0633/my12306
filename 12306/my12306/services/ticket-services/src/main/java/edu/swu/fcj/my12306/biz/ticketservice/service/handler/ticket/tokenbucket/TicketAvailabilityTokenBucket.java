package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.SeatStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.CacheUtil;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_TRAIN_STATION_TOKEN_BUCKET;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_TOKEN_BUCKET;

/**
 * Redis 余票令牌桶只负责购票准入，不是库存事实源。
 * 异常时始终降级放行，最终是否能占座仍由 MySQL 条件更新决定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketAvailabilityTokenBucket {

    private static final long TAKE_SUCCESS = 1L;

    private static final long TAKE_REJECTED = 0L;

    private static final long BUCKET_MISSING = -1L;

    private static final DefaultRedisScript<Long> TAKE_TOKEN_SCRIPT = loadScript(
            "lua/take_token_from_bucket.lua");

    private static final DefaultRedisScript<Long> RETURN_TOKEN_SCRIPT = loadScript(
            "lua/return_token_to_bucket.lua");

    private final StringRedisTemplate stringRedisTemplate;

    private final SeatMapper seatMapper;

    private final TrainMapper trainMapper;

    private final RedisCacheHelper redisCacheHelper;

    private final MeterRegistry meterRegistry;

    @Value("${my12306.availability.token-bucket.enabled:true}")
    private boolean enabled;

    @Value("${my12306.availability.token-bucket.expire-minutes:10}")
    private long expireMinutes;

    /**
     * 一次原子获取一张订单涉及的全部席别令牌。
     */
    public boolean takeToken(Long trainId, String departure, String arrival,
                             Map<Integer, Integer> seatTypeCountMap) {
        if (!enabled || seatTypeCountMap == null || seatTypeCountMap.isEmpty()) {
            return true;
        }
        Map<Integer, Integer> requested = normalize(seatTypeCountMap);
        if (requested.isEmpty()) {
            return true;
        }
        String bucketKey = buildBucketKey(trainId, departure, arrival);
        try {
            Long result = execute(TAKE_TOKEN_SCRIPT, bucketKey, requested);
            if (Long.valueOf(BUCKET_MISSING).equals(result)) {
                loadBucket(bucketKey, trainId, departure, arrival, requested.keySet().stream().toList());
                result = execute(TAKE_TOKEN_SCRIPT, bucketKey, requested);
            }
            if (Long.valueOf(TAKE_SUCCESS).equals(result)) {
                meterRegistry.counter("my12306.token.pass").increment();
                return true;
            }
            if (Long.valueOf(TAKE_REJECTED).equals(result)) {
                meterRegistry.counter("my12306.token.reject").increment();
                return false;
            }
            log.warn("令牌桶装载后仍缺少席别，降级放行，key={}，result={}", bucketKey, result);
            meterRegistry.counter("my12306.token.degrade").increment();
            return true;
        } catch (Exception ex) {
            log.warn("余票令牌桶取令异常，降级放行，key={}", bucketKey, ex);
            meterRegistry.counter("my12306.token.degrade").increment();
            return true;
        }
    }

    /**
     * 归还实际释放的座位令牌；桶已过期时脚本保持 Key 不存在。
     */
    public void returnToken(Long trainId, String departure, String arrival,
                            Map<Integer, Integer> seatTypeCountMap) {
        if (!enabled || seatTypeCountMap == null || seatTypeCountMap.isEmpty()) {
            return;
        }
        Map<Integer, Integer> returned = normalize(seatTypeCountMap);
        if (returned.isEmpty()) {
            return;
        }
        String bucketKey = buildBucketKey(trainId, departure, arrival);
        try {
            execute(RETURN_TOKEN_SCRIPT, bucketKey, returned);
        } catch (Exception ex) {
            log.warn("余票令牌归还异常，保留偏松降级并等待 TTL 自愈，key={}", bucketKey, ex);
            meterRegistry.counter("my12306.token.degrade").increment();
        }
    }

    private void loadBucket(String bucketKey, Long trainId, String departure, String arrival,
                            List<Integer> requestedSeatTypes) {
        String keySuffix = CacheUtil.buildKey(String.valueOf(trainId), departure, arrival);
        String lockKey = String.format(LOCK_TRAIN_STATION_TOKEN_BUCKET, keySuffix);
        redisCacheHelper.executeWithLock(lockKey, () -> {
            // Key 存在不代表字段完整；只要本次请求涉及的任一席别缺失，就重新装载完整桶。
            List<Object> fields = requestedSeatTypes.stream().map(String::valueOf).map(each -> (Object) each).toList();
            if (RedisCacheHelper.allNonNull(redisCacheHelper.hMultiGet(bucketKey, fields))) {
                return;
            }

            // 一次聚合查询取得区间各席别的真实可售数，减少装载阶段的 SQL 往返。
            QueryWrapper<SeatDO> query = new QueryWrapper<>();
            query.select("seat_type", "COUNT(*) AS cnt")
                    .eq("train_id", trainId)
                    .eq("start_station", departure)
                    .eq("end_station", arrival)
                    .eq("seat_status", SeatStatusEnum.AVAILABLE.getCode())
                    .eq("del_flag", 0)
                    .groupBy("seat_type");
            Map<Integer, Long> availableBySeatType = new LinkedHashMap<>();
            for (Map<String, Object> row : seatMapper.selectMaps(query)) {
                Object seatType = findValue(row, "seat_type");
                Object count = findValue(row, "cnt");
                if (seatType != null && count != null) {
                    availableBySeatType.put(Integer.valueOf(seatType.toString()), Long.valueOf(count.toString()));
                }
            }

            TrainDO train = trainMapper.selectById(trainId);
            if (train == null) {
                throw new IllegalStateException("令牌桶装载失败：车次不存在，trainId=" + trainId);
            }
            List<Integer> vehicleSeatTypes = VehicleTypeEnum.findSeatTypesByCode(train.getTrainType());
            if (vehicleSeatTypes.isEmpty()) {
                throw new IllegalStateException("令牌桶装载失败：车型没有席别，trainId=" + trainId);
            }
            Map<Object, Object> bucket = new LinkedHashMap<>();
            // 按车型支持的全部席别补 0，避免售完席别永久触发“field 缺失”重装。
            for (Integer seatType : vehicleSeatTypes) {
                bucket.put(String.valueOf(seatType), String.valueOf(availableBySeatType.getOrDefault(seatType, 0L)));
            }
            redisCacheHelper.hPutAllWithTtl(bucketKey, bucket, expireMinutes, TimeUnit.MINUTES);
            meterRegistry.counter("my12306.token.load").increment();
        });
    }

    private Long execute(DefaultRedisScript<Long> script, String bucketKey,
                         Map<Integer, Integer> seatTypeCountMap) {
        List<String> arguments = new ArrayList<>(seatTypeCountMap.size() * 2);
        seatTypeCountMap.forEach((seatType, count) -> {
            arguments.add(String.valueOf(seatType));
            arguments.add(String.valueOf(count));
        });
        return stringRedisTemplate.execute(script, Collections.singletonList(bucketKey), arguments.toArray());
    }

    private String buildBucketKey(Long trainId, String departure, String arrival) {
        return TRAIN_STATION_TOKEN_BUCKET
                + CacheUtil.buildKey(String.valueOf(trainId), departure, arrival);
    }

    private Map<Integer, Integer> normalize(Map<Integer, Integer> source) {
        Map<Integer, Integer> normalized = new TreeMap<>();
        source.forEach((seatType, count) -> {
            if (seatType != null && count != null && count > 0) {
                normalized.merge(seatType, count, Integer::sum);
            }
        });
        return normalized;
    }

    private Object findValue(Map<String, Object> row, String expectedKey) {
        return row.entrySet().stream()
                .filter(each -> each.getKey().toLowerCase(Locale.ROOT).equals(expectedKey))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static DefaultRedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
