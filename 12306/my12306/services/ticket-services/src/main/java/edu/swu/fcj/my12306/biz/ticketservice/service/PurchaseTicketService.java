package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketPurchaseRespDTO;

public interface PurchaseTicketService {

    /**
     * 购票：校验 → 座位分配 → 占座与车票记录 → 创建待支付订单
     */
    TicketPurchaseRespDTO purchaseTickets(PurchaseTicketReqDTO requestParam);
}
