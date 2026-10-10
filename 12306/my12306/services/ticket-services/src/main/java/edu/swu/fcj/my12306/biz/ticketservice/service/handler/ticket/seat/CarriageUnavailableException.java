package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat;

import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;

/** Only a local, fully rolled-back reservation attempt may be retried. */
public class CarriageUnavailableException extends ServiceException {
    public CarriageUnavailableException() {
        super("站点余票不足：候选车厢库存不足或占座冲突");
    }
}
