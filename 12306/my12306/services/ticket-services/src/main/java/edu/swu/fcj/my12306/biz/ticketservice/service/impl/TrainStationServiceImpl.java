package edu.swu.fcj.my12306.biz.ticketservice.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.StationCalculateUtil;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainStationDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainStationMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.RouteDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TrainStationQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.TrainStationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 列车站点接口实现层
 */
@Service
@RequiredArgsConstructor
public class TrainStationServiceImpl implements TrainStationService {

    private final TrainStationMapper trainStationMapper;

    @Override
    public List<TrainStationQueryRespDTO> listTrainStationQuery(String trainId) {
        if (StrUtil.isBlank(trainId)) {
            throw new ServiceException("列车ID不能为空");
        }
        List<TrainStationDO> trainStationDOList = trainStationMapper.selectList(Wrappers
                .lambdaQuery(TrainStationDO.class)
                .eq(TrainStationDO::getTrainId, parseTrainId(trainId))
                .orderByAsc(TrainStationDO::getSequence));
        return trainStationDOList.stream().map(each -> {
            TrainStationQueryRespDTO result = new TrainStationQueryRespDTO();
            result.setSequence(each.getSequence());
            result.setDeparture(each.getDeparture());
            result.setArrivalTime(each.getArrivalTime());
            result.setDepartureTime(each.getDepartureTime());
            result.setStopoverTime(each.getStopoverTime());
            return result;
        }).toList();
    }

    @Override
    public List<RouteDTO> listTrainStationRoute(String trainId, String departure, String arrival) {
        List<TrainStationDO> trainStationDOList = trainStationMapper.selectList(Wrappers
                .lambdaQuery(TrainStationDO.class)
                .eq(TrainStationDO::getTrainId, parseTrainId(trainId))
                .select(TrainStationDO::getDeparture)
                .orderByAsc(TrainStationDO::getSequence));
        List<String> trainStationAllList = trainStationDOList.stream().map(TrainStationDO::getDeparture).toList();
        return StationCalculateUtil.throughStation(trainStationAllList, departure, arrival);
    }

    private Long parseTrainId(String trainId) {
        try {
            return Long.valueOf(trainId);
        } catch (NumberFormatException e) {
            throw new ServiceException("列车ID格式错误");
        }
    }
}
