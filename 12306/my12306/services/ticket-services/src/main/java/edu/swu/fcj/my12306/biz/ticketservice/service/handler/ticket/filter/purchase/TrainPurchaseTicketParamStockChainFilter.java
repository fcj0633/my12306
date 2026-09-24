package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.filter.purchase;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.SeatStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 购票流程过滤器之三：按席别校验可售座位是否充足
 */
@Component
@RequiredArgsConstructor
public class TrainPurchaseTicketParamStockChainFilter implements TrainPurchaseTicketChainFilter {

    private final SeatMapper seatMapper;

    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        seatTypeMap.forEach((seatType, passengers) -> {
            Long available = seatMapper.selectCount(Wrappers.lambdaQuery(SeatDO.class)
                    .eq(SeatDO::getTrainId, Long.valueOf(requestParam.getTrainId()))
                    .eq(SeatDO::getSeatType, seatType)
                    .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                    .eq(SeatDO::getStartStation, requestParam.getDeparture())
                    .eq(SeatDO::getEndStation, requestParam.getArrival()));
            if (available == null || available < passengers.size()) {
                throw new ServiceException("列车站点已无余票");
            }
        });
    }

    @Override
    public int order() {
        return 20;
    }
}
