package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.filter.query;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.RegionDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.StationDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.RegionMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.StationMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_QUERY_ALL_REGION_LIST;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.QUERY_ALL_REGION_LIST;

/**
 * 查询车票流程过滤器之三：校验出发地/目的地编码是否存在（地区编码 ∪ 车站编码）
 */
@Component
@RequiredArgsConstructor
public class TrainTicketQueryParamVerifyChainFilter implements TrainTicketQueryChainFilter {

    private final RegionMapper regionMapper;

    private final StationMapper stationMapper;

    private final RedisCacheHelper redisCacheHelper;

    @Override
    public void handler(TicketPageQueryReqDTO requestParam) {
        List<Object> fields = List.of(requestParam.getFromStation(), requestParam.getToStation());
        if (RedisCacheHelper.allNonNull(redisCacheHelper.hMultiGet(QUERY_ALL_REGION_LIST, fields))) {
            return;
        }
        redisCacheHelper.executeWithLock(LOCK_QUERY_ALL_REGION_LIST, () -> {
            List<Object> current = redisCacheHelper.hMultiGet(QUERY_ALL_REGION_LIST, fields);
            if (RedisCacheHelper.allNonNull(current)) {
                return;
            }
            if (redisCacheHelper.hasKey(QUERY_ALL_REGION_LIST)) {
                return;
            }
            Map<Object, Object> regionValueMap = new HashMap<>();
            List<RegionDO> regionDOList = regionMapper.selectList(Wrappers.emptyWrapper());
            List<StationDO> stationDOList = stationMapper.selectList(Wrappers.emptyWrapper());
            for (RegionDO each : regionDOList) {
                if (StrUtil.isNotBlank(each.getCode())) {
                    regionValueMap.put(each.getCode(), each.getName());
                }
            }
            for (StationDO each : stationDOList) {
                if (StrUtil.isNotBlank(each.getCode())) {
                    regionValueMap.put(each.getCode(), each.getName());
                }
            }
            if (CollUtil.isNotEmpty(regionValueMap)) {
                redisCacheHelper.hPutAll(QUERY_ALL_REGION_LIST, regionValueMap);
            }
        });
        List<Object> finalValue = redisCacheHelper.hMultiGet(QUERY_ALL_REGION_LIST, fields);
        if (!RedisCacheHelper.allNonNull(finalValue)) {
            throw new ServiceException("出发地或目的地不存在");
        }
    }

    @Override
    public int order() {
        return 20;
    }
}
