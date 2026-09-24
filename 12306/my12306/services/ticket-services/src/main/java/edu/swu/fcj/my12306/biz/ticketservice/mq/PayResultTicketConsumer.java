package edu.swu.fcj.my12306.biz.ticketservice.mq;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.TicketStatusEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TicketDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TicketMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.TicketCallbackSeatDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketCallbackReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.mq.message.OrderPaidMessage;
import edu.swu.fcj.my12306.biz.ticketservice.service.TicketCallbackService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ORDER_PAID 的消费者：把座位推进为"已出售"、车票推进为"已支付"。
 * <p>
 * <b>为什么座位坐标从自己库里查，而不是让上游放进消息里：</b>
 * 车次/区间/席别/车厢/座号这些坐标，本服务在 t_ticket 里本来就记着
 * （购票时写入），而且本服务现成的孤儿恢复任务 {@code TicketOrphanRecoveryJob#buildCallback}
 * 就是这么取的。如果改成订单服务拼坐标，就会出现"两条做同一件事的路径坐标来源不同"的分歧风险 ——
 * 而孤儿恢复任务恰恰是通知丢失时兜底的那一条。让消费者读自己的表，两条路径就一致了。
 * <p>
 * <b>幂等不需要新机制：</b>复用 {@link TicketCallbackService#payCallback}，
 * 也就是 Feign 入口调用的同一个方法。重复投递时它对 t_ticket 的条件更新
 * （{@code WHERE ticket_status = UNPAID}）影响 0 行，从而跳过座位更新，天然成为空操作。
 * <p>
 * ⚠️ 不要 catch 异常后 return：RocketMQ 的自动 ACK 是"方法正常返回才 ACK"，
 * 吞掉异常会让消息永久丢失。本方法刻意不包 try/catch。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.pay.notify-mode", havingValue = "mq")
public class PayResultTicketConsumer implements MessageListenerConcurrently {

    private final TicketMapper ticketMapper;

    private final TicketCallbackService ticketCallbackService;

    private final MeterRegistry meterRegistry;

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        for (MessageExt msg : msgs) {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            OrderPaidMessage payload = JSON.parseObject(body, OrderPaidMessage.class);
            String orderSn = payload.getOrderSn();
            log.info("收到 ORDER_PAID，orderSn={}，reconsumeTimes={}", orderSn, msg.getReconsumeTimes());

            List<TicketDO> tickets = ticketMapper.selectList(Wrappers.lambdaQuery(TicketDO.class)
                    .eq(TicketDO::getOrderSn, orderSn));
            if (tickets.isEmpty()) {
                // 车票是购票时就写好的，订单已支付却一张票都没有，属于数据异常。
                // 抛出去进重试：可能是极端时序或数据未同步，重试有机会自愈；一直不好就进死信等人工处理。
                throw new IllegalStateException("该订单没有任何车票记录，orderSn=" + orderSn);
            }

            List<TicketDO> unpaidTickets = tickets.stream()
                    .filter(ticket -> TicketStatusEnum.UNPAID.getCode().equals(ticket.getTicketStatus()))
                    .toList();
            if (unpaidTickets.isEmpty()) {
                // 车票已不在"未支付" —— 例如订单此前已关闭、车票已被推进为 CANCELED。
                // 此时没有任何要推进的东西，属于**正常**情况。
                // 关键：这里必须 ACK 而不是抛异常。若抛异常，订单已关闭这类正常场景会变成
                // 一条永远重试、最终落进死信的"毒消息"。
                log.info("车票已非未支付状态，无需推进（可能订单已关闭）。orderSn={}", orderSn);
                continue;
            }

            ticketCallbackService.payCallback(buildPayCallback(orderSn, unpaidTickets));
            meterRegistry.counter("my12306.mq.order-paid.consumed").increment();
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    /**
     * 从本服务自己的车票记录拼出票务侧的回调入参。
     * <p>
     * 与 {@code TicketOrphanRecoveryJob#buildCallback} 是同构的实现（那边是从全部未支付车票拼，
     * 这里用的是消费时筛出的未支付车票）。两份代码刻意不合并：孤儿恢复任务是被测试覆盖的
     * 既有路径，动它的收益不足以抵消回归风险。
     */
    private TicketCallbackReqDTO buildPayCallback(String orderSn, List<TicketDO> unpaidTickets) {
        TicketDO first = unpaidTickets.getFirst();
        List<TicketCallbackSeatDTO> seats = unpaidTickets.stream().map(ticket -> {
            TicketCallbackSeatDTO seat = new TicketCallbackSeatDTO();
            seat.setCarriageNumber(ticket.getCarriageNumber());
            seat.setSeatNumber(ticket.getSeatNumber());
            seat.setSeatType(ticket.getSeatType());
            return seat;
        }).toList();
        TicketCallbackReqDTO callback = new TicketCallbackReqDTO();
        callback.setOrderSn(orderSn);
        callback.setTrainId(first.getTrainId());
        callback.setDeparture(first.getStartStation());
        callback.setArrival(first.getEndStation());
        callback.setSeats(seats);
        return callback;
    }
}
