package edu.swu.fcj.my12306.biz.ticketservice.dto.resp;

import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.TicketListDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 车次列表查询出参：车次列表 + 前端筛选项聚合
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketPageQueryRespDTO {

    private List<TicketListDTO> trainList;

    private List<Integer> trainBrandList;

    private List<String> departureStationList;

    private List<String> arrivalStationList;

    private List<Integer> seatClassTypeList;
}
