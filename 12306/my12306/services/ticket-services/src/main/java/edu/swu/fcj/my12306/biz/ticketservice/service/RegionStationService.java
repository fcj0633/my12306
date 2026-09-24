package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.dto.req.RegionStationQueryReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.RegionStationQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.StationQueryRespDTO;

import java.util.List;

public interface RegionStationService {

    /**
     * 查询地区/车站（关键词优先，否则按查询类型）
     */
    List<RegionStationQueryRespDTO> listRegionStation(RegionStationQueryReqDTO requestParam);

    /**
     * 查询全量车站
     */
    List<StationQueryRespDTO> listAllStation();
}
