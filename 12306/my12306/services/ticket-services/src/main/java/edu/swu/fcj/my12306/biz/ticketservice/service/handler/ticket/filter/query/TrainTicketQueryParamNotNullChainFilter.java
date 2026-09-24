package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.filter.query;

import cn.hutool.core.util.StrUtil;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.springframework.stereotype.Component;

/**
 * 查询车票流程过滤器之一：校验必填参数
 */
@Component
public class TrainTicketQueryParamNotNullChainFilter implements TrainTicketQueryChainFilter {

    @Override
    public void handler(TicketPageQueryReqDTO requestParam) {
        if (StrUtil.isBlank(requestParam.getFromStation())) {
            throw new ServiceException("出发地不能为空");
        }
        if (StrUtil.isBlank(requestParam.getToStation())) {
            throw new ServiceException("目的地不能为空");
        }
        if (requestParam.getDepartureDate() == null) {
            throw new ServiceException("出发日期不能为空");
        }
    }

    @Override
    public int order() {
        return 0;
    }
}
