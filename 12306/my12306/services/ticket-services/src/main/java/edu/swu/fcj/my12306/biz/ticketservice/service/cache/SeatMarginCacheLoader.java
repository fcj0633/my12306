package edu.swu.fcj.my12306.biz.ticketservice.service.cache;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.SeatStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.CacheUtil;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.RouteDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.TrainStationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_SAFE_LOAD_SEAT_MARGIN_GET;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 座位余量缓存加载：锁内双检 + 一次性补全整车区间余票
 */
@Component
@RequiredArgsConstructor
public class SeatMarginCacheLoader {

    private final TrainMapper trainMapper;

    private final SeatMapper seatMapper;

    private final RedisCacheHelper redisCacheHelper;

    private final TrainStationService trainStationService;

    public Map<String, String> load(String trainId, Integer seatType, String departure, String arrival) {
        String keySuffix = CacheUtil.buildKey(trainId, departure, arrival);
        String hashKey = TRAIN_STATION_REMAINING_TICKET + keySuffix;
        String field = String.valueOf(seatType);
        Object current = redisCacheHelper.hGet(hashKey, field);
        if (current != null) {
            return Map.of(field, current.toString());
        }

        String lockKey = String.format(LOCK_SAFE_LOAD_SEAT_MARGIN_GET, keySuffix);
        redisCacheHelper.executeWithLock(lockKey, () -> {
            if (redisCacheHelper.hGet(hashKey, field) != null) {
                return;
            }
            TrainDO trainDO = loadTrain(trainId);
            Map<String, Map<Object, Object>> pendingWrite = new LinkedHashMap<>();
            if (trainDO != null) {
                List<RouteDTO> routeDTOList = trainStationService.listTrainStationRoute(
                        trainId, trainDO.getStartStation(), trainDO.getEndStation());
                List<Integer> seatTypes = VehicleTypeEnum.findSeatTypesByCode(trainDO.getTrainType());
                for (RouteDTO route : routeDTOList) {
                    Map<Object, Object> remainingTicket = new LinkedHashMap<>();
                    for (Integer type : seatTypes) {
                        remainingTicket.put(String.valueOf(type),
                                selectSeatMargin(Long.valueOf(trainId), type, route.getStartStation(), route.getEndStation()));
                    }
                    pendingWrite.put(TRAIN_STATION_REMAINING_TICKET
                            + CacheUtil.buildKey(trainId, route.getStartStation(), route.getEndStation()), remainingTicket);
                }
            }
            if (pendingWrite.isEmpty()) {
                Map<Object, Object> zero = new LinkedHashMap<>();
                zero.put(field, "0");
                pendingWrite.put(hashKey, zero);
            }
            pendingWrite.forEach(redisCacheHelper::hPutAll);
            if (redisCacheHelper.hGet(hashKey, field) == null) {
                redisCacheHelper.hPut(hashKey, field, "0");
            }
        });

        Object value = redisCacheHelper.hGet(hashKey, field);
        Map<String, String> result = new LinkedHashMap<>();
        result.put(field, value == null ? "0" : value.toString());
        return result;
    }

    private TrainDO loadTrain(String trainId) {
        String cacheValue = redisCacheHelper.safeGet(TRAIN_INFO + trainId, () -> {
            TrainDO trainDO = trainMapper.selectById(Long.valueOf(trainId));
            return trainDO == null ? null : JSON.toJSONString(trainDO);
        }, ADVANCE_TICKET_DAY, TimeUnit.DAYS);
        return StrUtil.isBlank(cacheValue) ? null : JSON.parseObject(cacheValue, TrainDO.class);
    }

    /**
     * 统计某车次、某席别、某区间、可售状态下的座位数量
     */
    private String selectSeatMargin(Long trainId, Integer seatType, String departure, String arrival) {
        Long count = seatMapper.selectCount(Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, trainId)
                .eq(SeatDO::getSeatType, seatType)
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                .eq(SeatDO::getStartStation, departure)
                .eq(SeatDO::getEndStation, arrival));
        return count == null ? "0" : String.valueOf(count);
    }
}
