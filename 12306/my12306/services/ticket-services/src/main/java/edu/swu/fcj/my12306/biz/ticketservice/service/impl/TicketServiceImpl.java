package edu.swu.fcj.my12306.biz.ticketservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.chain.AbstractChainContext;
import edu.swu.fcj.my12306.biz.ticketservice.common.constant.TicketChainMarkEnum;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.CacheUtil;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.TicketDateUtil;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.TimeStringComparator;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.RegionDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.StationDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainStationRelationDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.RegionMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.StationMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainStationRelationMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.SeatClassDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.TicketListDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.TicketService;
import edu.swu.fcj.my12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_REGION_TRAIN_STATION;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_REGION_TRAIN_STATION_MAPPING;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_TRAIN_STATION_MAPPING;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_PRICE;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 车票查询接口实现：编码 → 城市 → 车次 → 区间票价与余票
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TrainMapper trainMapper;

    private final TrainStationRelationMapper trainStationRelationMapper;

    private final TrainStationPriceMapper trainStationPriceMapper;

    private final StationMapper stationMapper;

    private final RegionMapper regionMapper;

    private final RedisCacheHelper redisCacheHelper;

    private final SeatMarginCacheLoader seatMarginCacheLoader;

    private final AbstractChainContext ticketPageQueryAbstractChainContext;

    @Override
    public TicketPageQueryRespDTO pageListTicketQuery(TicketPageQueryReqDTO requestParam) {
        // 责任链：必填 → 基础规则 → 地点存在性
        ticketPageQueryAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);

        // 1. 车站编码 → 城市名
        List<Object> fields = List.of(requestParam.getFromStation(), requestParam.getToStation());
        List<Object> stationDetails = redisCacheHelper.hMultiGet(REGION_TRAIN_STATION_MAPPING, fields);
        if (!RedisCacheHelper.allNonNull(stationDetails)) {
            redisCacheHelper.executeWithLock(LOCK_REGION_TRAIN_STATION_MAPPING, () -> {
                if (!RedisCacheHelper.allNonNull(redisCacheHelper.hMultiGet(REGION_TRAIN_STATION_MAPPING, fields))
                        && !redisCacheHelper.hasKey(REGION_TRAIN_STATION_MAPPING)) {
                    Map<Object, Object> mapping = loadRegionTrainStationMapping();
                    if (CollUtil.isNotEmpty(mapping)) {
                        redisCacheHelper.hPutAll(REGION_TRAIN_STATION_MAPPING, mapping);
                    }
                }
            });
            stationDetails = redisCacheHelper.hMultiGet(REGION_TRAIN_STATION_MAPPING, fields);
        }
        if (!RedisCacheHelper.allNonNull(stationDetails)) {
            throw new ServiceException("出发地或目的地不存在");
        }
        String fromRegion = stationDetails.get(0).toString();
        String toRegion = stationDetails.get(1).toString();

        // 2. 城市对 → 车次列表（hash 缓存 + 锁回源）
        String regionTrainStationKey = String.format(REGION_TRAIN_STATION, fromRegion, toRegion);
        Map<Object, Object> regionTrainStationMap = redisCacheHelper.hGetAll(regionTrainStationKey);
        if (CollUtil.isEmpty(regionTrainStationMap)) {
            redisCacheHelper.executeWithLock(LOCK_REGION_TRAIN_STATION, () -> {
                if (CollUtil.isEmpty(redisCacheHelper.hGetAll(regionTrainStationKey))) {
                    redisCacheHelper.hPutAll(regionTrainStationKey, loadRegionTrainStation(fromRegion, toRegion));
                }
            });
            regionTrainStationMap = redisCacheHelper.hGetAll(regionTrainStationKey);
        }
        List<TicketListDTO> seatResults = regionTrainStationMap.values().stream()
                .map(each -> JSON.parseObject(each.toString(), TicketListDTO.class))
                .sorted(new TimeStringComparator())
                .collect(Collectors.toList());

        // 3. 逐车次补票价与余票
        for (TicketListDTO each : seatResults) {
            List<TrainStationPriceDO> trainStationPriceDOList = loadTrainStationPrice(each);
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            for (TrainStationPriceDO priceDO : trainStationPriceDOList) {
                String remainingHashKey = TRAIN_STATION_REMAINING_TICKET
                        + CacheUtil.buildKey(each.getTrainId(), each.getDeparture(), each.getArrival());
                Object quantityObj = redisCacheHelper.hGet(remainingHashKey, String.valueOf(priceDO.getSeatType()));
                int quantity;
                if (quantityObj == null) {
                    Map<String, String> seatMarginMap = seatMarginCacheLoader.load(
                            each.getTrainId(), priceDO.getSeatType(), each.getDeparture(), each.getArrival());
                    quantity = Integer.parseInt(seatMarginMap.getOrDefault(String.valueOf(priceDO.getSeatType()), "0"));
                } else {
                    quantity = Integer.parseInt(quantityObj.toString());
                }
                BigDecimal price = new BigDecimal(priceDO.getPrice())
                        .divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP);
                seatClassList.add(SeatClassDTO.builder()
                        .type(priceDO.getSeatType())
                        .quantity(quantity)
                        .price(price)
                        .candidate(false)
                        .build());
            }
            each.setSeatClassList(seatClassList);
        }

        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassTypeList(seatResults))
                .build();
    }

    /**
     * 车站编码 → 城市名映射：车站优先，地区编码补充（兼容按地区编码查询）
     */
    private Map<Object, Object> loadRegionTrainStationMapping() {
        Map<Object, Object> mapping = new HashMap<>();
        List<StationDO> stationDOList = stationMapper.selectList(Wrappers.emptyWrapper());
        for (StationDO each : stationDOList) {
            if (StrUtil.isNotBlank(each.getCode()) && StrUtil.isNotBlank(each.getRegionName())) {
                mapping.put(each.getCode(), each.getRegionName());
            }
        }
        List<RegionDO> regionDOList = regionMapper.selectList(Wrappers.emptyWrapper());
        for (RegionDO each : regionDOList) {
            if (StrUtil.isNotBlank(each.getCode())) {
                mapping.putIfAbsent(each.getCode(), each.getName());
            }
        }
        return mapping;
    }

    /**
     * 城市对 → 车次列表：查可售区间并组装
     */
    private Map<Object, Object> loadRegionTrainStation(String fromRegion, String toRegion) {
        List<TrainStationRelationDO> relationList = trainStationRelationMapper.selectList(Wrappers
                .lambdaQuery(TrainStationRelationDO.class)
                .eq(TrainStationRelationDO::getStartRegion, fromRegion)
                .eq(TrainStationRelationDO::getEndRegion, toRegion));
        Map<Object, Object> result = new LinkedHashMap<>();
        for (TrainStationRelationDO each : relationList) {
            TrainDO trainDO = loadTrain(String.valueOf(each.getTrainId()));
            if (trainDO == null) {
                log.warn("车次数据缺失，已跳过。trainId={}", each.getTrainId());
                continue;
            }
            TicketListDTO ticketListDTO = buildTicketList(each, trainDO);
            result.put(CacheUtil.buildKey(String.valueOf(each.getTrainId()), each.getDeparture(), each.getArrival()),
                    JSON.toJSONString(ticketListDTO));
        }
        return result;
    }

    private TicketListDTO buildTicketList(TrainStationRelationDO relation, TrainDO trainDO) {
        TicketListDTO result = TicketListDTO.builder()
                .trainId(String.valueOf(trainDO.getId()))
                .trainNumber(trainDO.getTrainNumber())
                .departureTime(TicketDateUtil.convertDateToLocalTime(relation.getDepartureTime(), "HH:mm"))
                .arrivalTime(TicketDateUtil.convertDateToLocalTime(relation.getArrivalTime(), "HH:mm"))
                .duration(TicketDateUtil.calculateHourDifference(relation.getDepartureTime(), relation.getArrivalTime()))
                .daysArrived((int) cn.hutool.core.date.DateUtil.betweenDay(
                        relation.getDepartureTime(), relation.getArrivalTime(), false))
                .departure(relation.getDeparture())
                .arrival(relation.getArrival())
                .departureFlag(relation.getDepartureFlag())
                .arrivalFlag(relation.getArrivalFlag())
                .trainType(trainDO.getTrainType())
                .trainBrand(trainDO.getTrainBrand())
                .saleStatus(trainDO.getSaleTime() != null && new Date().after(trainDO.getSaleTime()) ? 0 : 1)
                .saleTime(trainDO.getSaleTime() == null ? null
                        : TicketDateUtil.convertDateToLocalTime(trainDO.getSaleTime(), "MM-dd HH:mm"))
                .build();
        if (StrUtil.isNotBlank(trainDO.getTrainTag())) {
            result.setTrainTags(StrUtil.split(trainDO.getTrainTag(), ","));
        }
        return result;
    }

    private TrainDO loadTrain(String trainId) {
        String cacheValue = redisCacheHelper.safeGet(TRAIN_INFO + trainId, () -> {
            TrainDO trainDO = trainMapper.selectById(Long.valueOf(trainId));
            return trainDO == null ? null : JSON.toJSONString(trainDO);
        }, ADVANCE_TICKET_DAY, TimeUnit.DAYS);
        return StrUtil.isBlank(cacheValue) ? null : JSON.parseObject(cacheValue, TrainDO.class);
    }

    private List<TrainStationPriceDO> loadTrainStationPrice(TicketListDTO ticketListDTO) {
        String cacheKey = String.format(TRAIN_STATION_PRICE,
                ticketListDTO.getTrainId(), ticketListDTO.getDeparture(), ticketListDTO.getArrival());
        String cacheValue = redisCacheHelper.safeGet(cacheKey, () -> {
            List<TrainStationPriceDO> priceList = trainStationPriceMapper.selectList(Wrappers
                    .lambdaQuery(TrainStationPriceDO.class)
                    .eq(TrainStationPriceDO::getTrainId, Long.valueOf(ticketListDTO.getTrainId()))
                    .eq(TrainStationPriceDO::getDeparture, ticketListDTO.getDeparture())
                    .eq(TrainStationPriceDO::getArrival, ticketListDTO.getArrival()));
            return CollUtil.isEmpty(priceList) ? null : JSON.toJSONString(priceList);
        }, ADVANCE_TICKET_DAY, TimeUnit.DAYS);
        return StrUtil.isBlank(cacheValue)
                ? Collections.emptyList()
                : JSON.parseArray(cacheValue, TrainStationPriceDO.class);
    }

    private List<String> buildDepartureStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getDeparture)
                .filter(Objects::nonNull).distinct().toList();
    }

    private List<String> buildArrivalStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getArrival)
                .filter(Objects::nonNull).distinct().toList();
    }

    private List<Integer> buildSeatClassTypeList(List<TicketListDTO> seatResults) {
        return seatResults.stream()
                .filter(each -> CollUtil.isNotEmpty(each.getSeatClassList()))
                .flatMap(each -> each.getSeatClassList().stream())
                .map(SeatClassDTO::getType)
                .distinct()
                .toList();
    }

    private List<Integer> buildTrainBrandList(List<TicketListDTO> seatResults) {
        return seatResults.stream()
                .map(TicketListDTO::getTrainBrand)
                .filter(StrUtil::isNotBlank)
                .flatMap(brand -> StrUtil.split(brand, ",").stream())
                .map(Integer::parseInt)
                .distinct()
                .toList();
    }
}
