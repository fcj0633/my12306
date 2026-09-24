package edu.swu.fcj.my12306.biz.ticketservice.dto.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 区间（出发站 → 到达站）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteDTO {

    private String startStation;

    private String endStation;
}
