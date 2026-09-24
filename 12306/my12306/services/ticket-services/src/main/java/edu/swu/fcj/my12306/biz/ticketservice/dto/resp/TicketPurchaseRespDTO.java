package edu.swu.fcj.my12306.biz.ticketservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 购票结果：订单号 + 乘车人明细
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketPurchaseRespDTO {

    private String orderSn;

    private List<TicketOrderDetailRespDTO> ticketOrderDetails;
}
