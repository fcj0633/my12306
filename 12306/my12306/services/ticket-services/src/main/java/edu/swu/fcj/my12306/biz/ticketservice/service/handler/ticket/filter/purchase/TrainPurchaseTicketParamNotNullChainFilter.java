package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.filter.purchase;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 购票流程过滤器之一：参数必填
 */
@Component
public class TrainPurchaseTicketParamNotNullChainFilter implements TrainPurchaseTicketChainFilter {

    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        if (StrUtil.isBlank(requestParam.getTrainId())) {
            throw new ServiceException("列车标识不能为空");
        }
        if (StrUtil.isBlank(requestParam.getDeparture())) {
            throw new ServiceException("出发站点不能为空");
        }
        if (StrUtil.isBlank(requestParam.getArrival())) {
            throw new ServiceException("到达站点不能为空");
        }
        if (CollUtil.isEmpty(requestParam.getPassengers())) {
            throw new ServiceException("乘车人至少选择一位");
        }
        for (PurchaseTicketPassengerDetailDTO each : requestParam.getPassengers()) {
            if (StrUtil.isBlank(each.getPassengerId())) {
                throw new ServiceException("乘车人不能为空");
            }
            if (Objects.isNull(each.getSeatType())) {
                throw new ServiceException("座位类型不能为空");
            }
        }
    }

    @Override
    public int order() {
        return 0;
    }
}
