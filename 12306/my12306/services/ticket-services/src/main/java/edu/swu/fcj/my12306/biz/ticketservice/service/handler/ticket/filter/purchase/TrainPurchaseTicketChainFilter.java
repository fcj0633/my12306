package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.filter.purchase;

import edu.swu.fcj.my12306.biz.ticketservice.common.chain.AbstractChainHandler;
import edu.swu.fcj.my12306.biz.ticketservice.common.constant.TicketChainMarkEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;

/**
 * 购票责任链过滤器
 */
public interface TrainPurchaseTicketChainFilter extends AbstractChainHandler<PurchaseTicketReqDTO> {

    @Override
    default String mark() {
        return TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name();
    }
}
