package edu.swu.fcj.my12306.biz.ticketservice.controller;

import edu.swu.fcj.my12306.biz.ticketservice.common.Result;
import edu.swu.fcj.my12306.biz.ticketservice.common.Results;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TrainStationQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.TrainStationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 列车站点控制层
 */
@RestController
@RequiredArgsConstructor
public class TrainStationController {

    private final TrainStationService trainStationService;

    /**
     * 根据列车 ID 查询经停站信息
     */
    @GetMapping("/api/ticket-service/train-station/query")
    public Result<List<TrainStationQueryRespDTO>> listTrainStationQuery(String trainId) {
        return Results.success(trainStationService.listTrainStationQuery(trainId));
    }
}
