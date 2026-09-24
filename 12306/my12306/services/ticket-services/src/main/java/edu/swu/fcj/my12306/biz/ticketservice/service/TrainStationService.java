package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.RouteDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TrainStationQueryRespDTO;

import java.util.List;

public interface TrainStationService {

    /**
     * 根据车次 ID 查询经停站（按停靠顺序）
     */
    List<TrainStationQueryRespDTO> listTrainStationQuery(String trainId);

    /**
     * 计算车次在指定起止站之间的全部子区间（余票回源使用）
     */
    List<RouteDTO> listTrainStationRoute(String trainId, String departure, String arrival);
}
