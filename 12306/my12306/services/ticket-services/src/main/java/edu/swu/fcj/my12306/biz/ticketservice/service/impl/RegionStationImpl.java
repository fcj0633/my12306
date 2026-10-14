package edu.swu.fcj.my12306.biz.ticketservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.RegionStationQueryTypeEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.RegionDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.StationDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.RegionMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.StationMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.RegionStationQueryReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.RegionStationQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.StationQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.RegionStationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_QUERY_REGION_STATION_LIST;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.REGION_STATION;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.STATION_ALL;

/**
 * 地区以及车站接口实现层
 */
@Service
@RequiredArgsConstructor
public class RegionStationImpl implements RegionStationService {

    private final RegionMapper regionMapper;

    private final StationMapper stationMapper;

    private final RedisCacheHelper redisCacheHelper;

    @Override
    public List<RegionStationQueryRespDTO> listRegionStation(RegionStationQueryReqDTO requestParam) {
        if (StrUtil.isNotBlank(requestParam.getName())) {
            String cacheKey = REGION_STATION + requestParam.getName();
            String lockKey = String.format(LOCK_QUERY_REGION_STATION_LIST, requestParam.getName());
            String cacheValue = getOrLoad(cacheKey, lockKey, () -> {
                List<StationDO> stationDOList = stationMapper.selectList(Wrappers.lambdaQuery(StationDO.class)
                        .likeRight(StationDO::getName, requestParam.getName())
                        .or()
                        .likeRight(StationDO::getSpell, requestParam.getName()));
                if (CollUtil.isEmpty(stationDOList)) {
                    return null;
                }
                return JSON.toJSONString(stationDOList.stream()
                        .map(each -> new RegionStationQueryRespDTO(each.getName(), each.getCode(), each.getSpell()))
                        .toList());
            });
            return StrUtil.isBlank(cacheValue)
                    ? Collections.emptyList()
                    : JSON.parseArray(cacheValue, RegionStationQueryRespDTO.class);
        }

        Integer queryType = requestParam.getQueryType();
        List<String> spells = RegionStationQueryTypeEnum.findSpellsByType(queryType);
        if (queryType == null || (queryType != 0 && CollUtil.isEmpty(spells))) {
            throw new ServiceException("查询失败，请检查查询参数是否正确");
        }
        String cacheKey = REGION_STATION + queryType;
        String lockKey = String.format(LOCK_QUERY_REGION_STATION_LIST, queryType);
        String cacheValue = getOrLoad(cacheKey, lockKey, () -> {
            LambdaQueryWrapper<RegionDO> queryWrapper = queryType == 0
                    ? Wrappers.lambdaQuery(RegionDO.class).eq(RegionDO::getPopularFlag, 1)
                    : Wrappers.lambdaQuery(RegionDO.class).in(RegionDO::getInitial, spells);
            List<RegionDO> regionDOList = regionMapper.selectList(queryWrapper);
            if (CollUtil.isEmpty(regionDOList)) {
                return null;
            }
            return JSON.toJSONString(regionDOList.stream()
                    .map(each -> new RegionStationQueryRespDTO(each.getName(), each.getCode(), each.getSpell()))
                    .toList());
        });
        return StrUtil.isBlank(cacheValue)
                ? Collections.emptyList()
                : JSON.parseArray(cacheValue, RegionStationQueryRespDTO.class);
    }

    @Override
    public List<StationQueryRespDTO> listAllStation() {
        String cacheValue = redisCacheHelper.safeGet(STATION_ALL, () -> {
            List<StationDO> stationDOList = stationMapper.selectList(Wrappers.emptyWrapper());
            if (CollUtil.isEmpty(stationDOList)) {
                return null;
            }
            return JSON.toJSONString(stationDOList.stream()
                    .map(each -> new StationQueryRespDTO(each.getName(), each.getCode(), each.getSpell(), each.getRegionName()))
                    .toList());
        }, ADVANCE_TICKET_DAY, TimeUnit.DAYS);
        return StrUtil.isBlank(cacheValue)
                ? Collections.emptyList()
                : JSON.parseArray(cacheValue, StationQueryRespDTO.class);
    }

    /**
     * 按实际缓存 Key 加锁双检；正常空结果由缓存层编码并设置短 TTL。
     */
    private String getOrLoad(String cacheKey, String lockKey, Supplier<String> loader) {
        return redisCacheHelper.safeGet(cacheKey, loader, ADVANCE_TICKET_DAY, TimeUnit.DAYS);
    }
}
