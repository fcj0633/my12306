package edu.swu.fcj.my12306.biz.ticketservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TicketDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TicketMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketCallbackReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.TicketCallbackService;
import edu.swu.fcj.my12306.biz.ticketservice.service.cache.InventoryCacheEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;
import java.util.HashSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketCallbackServiceImpl implements TicketCallbackService {
    private final SeatMapper seatMapper;
    private final TicketMapper ticketMapper;
    private final InventoryRouteService routes;
    private final InventoryCacheEvents cacheEvents;

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Boolean payCallback(TicketCallbackReqDTO request) { return transition(request, false); }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Boolean cancelCallback(TicketCallbackReqDTO request) { return transition(request, true); }

    private Boolean transition(TicketCallbackReqDTO request, boolean cancel) {
        if (request == null || request.getTrainId() == null || blank(request.getOrderSn())
                || blank(request.getDeparture()) || blank(request.getArrival())
                || request.getSeats() == null || request.getSeats().isEmpty()) {
            throw new ServiceException("回调参数不完整");
        }
        var requested=new HashSet<String>();
        for (var seat:request.getSeats()) {
            if (seat == null || seat.getSeatType() == null || blank(seat.getCarriageNumber())
                    || blank(seat.getSeatNumber())
                    || !requested.add(coordinate(seat.getSeatType(),seat.getCarriageNumber(),seat.getSeatNumber()))) {
                throw new ServiceException("回调座位参数错误或重复");
            }
        }
        // Lock the order's tickets in id order, then claim each unpaid ticket before changing segments.
        var tickets=ticketMapper.selectList(Wrappers.lambdaQuery(TicketDO.class)
                .eq(TicketDO::getOrderSn,request.getOrderSn()).eq(TicketDO::getTrainId,request.getTrainId())
                .eq(TicketDO::getStartStation,request.getDeparture()).eq(TicketDO::getEndStation,request.getArrival())
                .orderByAsc(TicketDO::getId).last("FOR UPDATE"));
        boolean released=false;
        var range=routes.resolve(request.getTrainId(),request.getDeparture(),request.getArrival());
        for (var ticket:tickets) {
            if (!requested.contains(coordinate(ticket.getSeatType(),ticket.getCarriageNumber(),ticket.getSeatNumber()))
                    || !Integer.valueOf(0).equals(ticket.getTicketStatus())) continue;
            TicketDO update=new TicketDO();
            update.setTicketStatus(cancel?30:10);update.setUpdateTime(new Date());
            int claimed=ticketMapper.update(update,Wrappers.lambdaUpdate(TicketDO.class)
                    .eq(TicketDO::getId,ticket.getId()).eq(TicketDO::getTicketStatus,0));
            if (claimed != 1) throw new ServiceException("回调车票状态认领失败");
            int affected=cancel
                    ?seatMapper.releaseOwnedSegments(ticket.getId(),ticket.getOrderSn(),range.start(),range.end())
                    :seatMapper.sellOwnedSegments(ticket.getId(),ticket.getOrderSn(),range.start(),range.end(),2);
            if (affected != range.length()) {
                log.error("车票区段归属异常，回滚回调。ticketId={} expected={} affected={}",ticket.getId(),range.length(),affected);
                throw new ServiceException("车票区段归属异常，回调已回滚");
            }
            released |= cancel;
        }
        if (released) cacheEvents.enqueue(request.getTrainId(),range,true);
        return Boolean.TRUE;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String coordinate(Integer type,String carriage,String seat) { return type+"/"+carriage+"/"+seat; }
}
