package edu.swu.fcj.my12306.biz.ticketservice.dto.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 车次席别信息：席别 + 余票 + 票价
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatClassDTO {

    private Integer type;

    private Integer quantity;

    private BigDecimal price;

    private Boolean candidate;
}
