package edu.swu.fcj.my12306.biz.ticketservice.controller;

import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.common.Results;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.RegionStationQueryReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.RegionStationQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.StationQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.RegionStationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 地区以及车站查询控制层
 */
@RestController
@RequiredArgsConstructor
public class RegionStationController {

    private final RegionStationService regionStationService;

    /**
     * 查询车站/地区集合信息
     */
    @GetMapping("/api/ticket-service/region-station/query")
    public Result<List<RegionStationQueryRespDTO>> listRegionStation(RegionStationQueryReqDTO requestParam) {
        return Results.success(regionStationService.listRegionStation(requestParam));
    }

    /**
     * 查询全量车站集合信息
     */
    @GetMapping("/api/ticket-service/station/all")
    public Result<List<StationQueryRespDTO>> listAllStation() {
        return Results.success(regionStationService.listAllStation());
    }
}
