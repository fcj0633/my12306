package edu.swu.fcj.my12306.biz.ticketservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全量车站出参
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StationQueryRespDTO {

    private String name;

    private String code;

    private String spell;

    private String regionName;
}
