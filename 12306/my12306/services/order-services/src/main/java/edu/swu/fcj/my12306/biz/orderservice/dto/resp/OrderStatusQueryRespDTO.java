package edu.swu.fcj.my12306.biz.orderservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal internal view used by ticket recovery; absence is data, not an exception.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusQueryRespDTO {

    private Boolean exists;

    private Integer status;
}
