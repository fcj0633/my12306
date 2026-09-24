package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.filter.query;

import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 查询车票流程过滤器之二：校验业务规则（日期、出发地与目的地）
 */
@Component
public class TrainTicketQueryParamBaseVerifyChainFilter implements TrainTicketQueryChainFilter {

    @Override
    public void handler(TicketPageQueryReqDTO requestParam) {
        LocalDate departureDate = requestParam.getDepartureDate().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDate();
        if (departureDate.isBefore(LocalDate.now())) {
            throw new ServiceException("出发日期不能小于当前日期");
        }
        if (Objects.equals(requestParam.getFromStation(), requestParam.getToStation())) {
            throw new ServiceException("出发地和目的地不能相同");
        }
    }

    @Override
    public int order() {
        return 10;
    }
}
