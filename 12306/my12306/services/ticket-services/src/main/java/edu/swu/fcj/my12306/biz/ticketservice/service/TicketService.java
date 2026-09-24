package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;

public interface TicketService {

    /**
     * 根据出发地、目的地、日期查询车次及票价余票
     */
    TicketPageQueryRespDTO pageListTicketQuery(TicketPageQueryReqDTO requestParam);
}
