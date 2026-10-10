package edu.swu.fcj.my12306.biz.ticketservice.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.*;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.*;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseMetadata;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO;

/** Read before resource locking; reuse this snapshot across candidate/transaction retries. */
@Service
@RequiredArgsConstructor
public class PurchaseMetadataService {
    private final TrainMapper trainMapper;
    private final TrainStationRelationMapper relationMapper;
    private final TrainStationPriceMapper priceMapper;
    private final RedisCacheHelper redisCacheHelper;
    private final MeterRegistry meterRegistry;

    public PurchaseMetadata prepare(PurchaseTicketReqDTO request) {
        return meterRegistry.timer("my12306.purchase.metadata.prepare").record(() -> load(request));
    }

    private PurchaseMetadata load(PurchaseTicketReqDTO request) {
        String cached = redisCacheHelper.safeGet(TRAIN_INFO + request.getTrainId(), () -> {
            TrainDO train = trainMapper.selectById(Long.valueOf(request.getTrainId()));
            return train == null ? null : JSON.toJSONString(train);
        }, ADVANCE_TICKET_DAY, TimeUnit.DAYS);
        TrainDO train = StrUtil.isBlank(cached) ? null : JSON.parseObject(cached, TrainDO.class);
        if (train == null) throw new ServiceException("请检查车次是否存在");
        TrainStationRelationDO relation = relationMapper.selectOne(Wrappers.lambdaQuery(TrainStationRelationDO.class)
                .eq(TrainStationRelationDO::getTrainId, Long.valueOf(request.getTrainId()))
                .eq(TrainStationRelationDO::getDeparture, request.getDeparture())
                .eq(TrainStationRelationDO::getArrival, request.getArrival()));
        if (relation == null) throw new ServiceException("列车车站数据错误");
        Map<Integer, Integer> amounts = new LinkedHashMap<>();
        for (Integer type : request.getPassengers().stream().map(PurchaseTicketPassengerDetailDTO::getSeatType).distinct().toList()) {
            TrainStationPriceDO price = priceMapper.selectOne(Wrappers.lambdaQuery(TrainStationPriceDO.class)
                    .eq(TrainStationPriceDO::getTrainId, Long.valueOf(request.getTrainId()))
                    .eq(TrainStationPriceDO::getDeparture, request.getDeparture())
                    .eq(TrainStationPriceDO::getArrival, request.getArrival())
                    .eq(TrainStationPriceDO::getSeatType, type));
            if (price == null || price.getPrice() == null) throw new ServiceException("车票价格数据缺失");
            amounts.put(type, price.getPrice());
        }
        return new PurchaseMetadata(train.getTrainNumber(),
                relation.getDepartureTime() == null ? null : relation.getDepartureTime().toInstant(),
                relation.getArrivalTime() == null ? null : relation.getArrivalTime().toInstant(), amounts);
    }
}
