package edu.swu.fcj.my12306.biz.ticketservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 地区/车站查询出参
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegionStationQueryRespDTO {

    private String name;

    private String code;

    private String spell;
}
