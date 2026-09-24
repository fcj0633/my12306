package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.filter.query;

import edu.swu.fcj.my12306.biz.ticketservice.common.chain.AbstractChainHandler;
import edu.swu.fcj.my12306.biz.ticketservice.common.constant.TicketChainMarkEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;

/**
 * 车票查询责任链过滤器
 */
public interface TrainTicketQueryChainFilter extends AbstractChainHandler<TicketPageQueryReqDTO> {

    @Override
    default String mark() {
        return TicketChainMarkEnum.TRAIN_QUERY_FILTER.name();
    }
}
