package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.filter.purchase;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson2.JSON;
import cn.hutool.core.util.StrUtil;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.TrainStationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;

/**
 * 购票流程过滤器之二：车次与区间合法性
 * <p>
 * 时间校验（已开售/未发车）通过配置开启；样例为静态历史数据，默认关闭，保留为【待确认 #2/#11】。
 */
@Component
@RequiredArgsConstructor
public class TrainPurchaseTicketParamVerifyChainFilter implements TrainPurchaseTicketChainFilter {

    private final TrainMapper trainMapper;

    private final RedisCacheHelper redisCacheHelper;

    private final TrainStationService trainStationService;

    @Value("${my12306.purchase.time-check-enabled:false}")
    private boolean timeCheckEnabled;

    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        TrainDO trainDO = loadTrain(requestParam.getTrainId());
        if (trainDO == null) {
            throw new ServiceException("请检查车次是否存在");
        }
        if (timeCheckEnabled) {
            if (trainDO.getSaleTime() != null && new Date().before(trainDO.getSaleTime())) {
                throw new ServiceException("列车车次暂未发售");
            }
            if (trainDO.getDepartureTime() != null && new Date().after(trainDO.getDepartureTime())) {
                throw new ServiceException("列车车次已出发禁止购票");
            }
        }
        if (Objects.equals(requestParam.getDeparture(), requestParam.getArrival())
                || CollUtil.isEmpty(trainStationService.listTrainStationRoute(
                        requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival()))) {
            throw new ServiceException("列车车站数据错误");
        }
    }

    private TrainDO loadTrain(String trainId) {
        String cacheValue = redisCacheHelper.safeGet(TRAIN_INFO + trainId, () -> {
            TrainDO trainDO = trainMapper.selectById(Long.valueOf(trainId));
            return trainDO == null ? null : JSON.toJSONString(trainDO);
        }, ADVANCE_TICKET_DAY, TimeUnit.DAYS);
        return StrUtil.isBlank(cacheValue) ? null : JSON.parseObject(cacheValue, TrainDO.class);
    }

    @Override
    public int order() {
        return 10;
    }
}
