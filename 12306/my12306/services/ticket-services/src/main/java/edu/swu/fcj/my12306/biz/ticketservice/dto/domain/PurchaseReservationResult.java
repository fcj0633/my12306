package edu.swu.fcj.my12306.biz.ticketservice.dto.domain;

import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketCallbackReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketOrderDetailRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;

import java.util.List;

/**
 * Durable result of the local purchase transaction. It contains everything the orchestration
 * layer needs to create the order or compensate without reopening seat-allocation logic.
 */
public record PurchaseReservationResult(
        TicketOrderCreateRemoteReqDTO orderCreateRequest,
        List<TicketOrderDetailRespDTO> ticketOrderDetails,
        TicketCallbackReqDTO compensationRequest) {
}
