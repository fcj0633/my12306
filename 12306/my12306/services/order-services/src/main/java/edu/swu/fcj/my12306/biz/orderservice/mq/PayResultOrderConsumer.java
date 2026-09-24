package edu.swu.fcj.my12306.biz.orderservice.mq;

import com.alibaba.fastjson2.JSON;
import edu.swu.fcj.my12306.biz.orderservice.common.PayNotifyMqConstants;
import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderPayCallbackReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.mq.message.OrderPaidMessage;
import edu.swu.fcj.my12306.biz.orderservice.mq.message.PaySuccessMessage;
import edu.swu.fcj.my12306.biz.orderservice.service.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * PAY_SUCCESS 的消费者：把订单推进成"已支付"，然后发 ORDER_PAID 通知票务。
 * <p>
 * 这里体现的是链式事件驱动的两个要点：
 * <p>
 * <b>① 订单必须【先】被推进。</b> 顺序需求是真实的：如果票务先改（座位 SOLD）而订单仍是待支付，
 * 超时关单任务可能扫到这笔订单 → 关单 → 释放座位，而用户其实已经付钱了 → 超卖。
 * 所以不做"两个消费者并行消费 PAY_SUCCESS"，而是让票务订阅 order 成功后才发出的 ORDER_PAID。
 * <p>
 * <b>② 消费端幂等不需要新机制。</b> 直接复用 {@link OrderService#payCallbackOrder} ——
 * 也就是 Feign 入口调用的同一个方法。它的幂等来自"条件更新 + 影响行数"：
 * {@code UPDATE t_order ... WHERE order_sn=? AND status=0}，影响 0 行说明已被处理过。
 * MQ 只是换了触发源，业务侧一行都不用改。
 * <p>
 * ⚠️ <b>不要在这里 catch 异常后 return。</b>
 * RocketMQ 的自动 ACK 语义是"消费方法【正常返回】才 ACK"：
 * 一旦捕获异常还返回 CONSUME_SUCCESS，消息会被当成消费成功而【永久丢失】。
 * 必须让异常抛出去，才会进重试队列（默认 16 次后进死信）。
 * 本方法刻意不包 try/catch —— 这是有意的，不是漏了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.pay.notify-mode", havingValue = "mq")
public class PayResultOrderConsumer implements MessageListenerConcurrently {

    private final OrderService orderService;

    private final MeterRegistry meterRegistry;

    private final ObjectProvider<DefaultMQProducer> producerProvider;

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        for (MessageExt msg : msgs) {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            PaySuccessMessage payload = JSON.parseObject(body, PaySuccessMessage.class);
            log.info("收到 PAY_SUCCESS，orderSn={}，paySn={}，reconsumeTimes={}",
                    payload.getOrderSn(), payload.getPaySn(), msg.getReconsumeTimes());

            // ① 推进订单状态。复用 Feign 入口的同一个方法，幂等能力原样继承。
            orderService.payCallbackOrder(buildPayCallbackReq(payload));

            // ② 链式：订单推进成功后再通知票务。
            //    这里【不】判断订单是否真的变成了已支付 —— 因为票务侧的条件更新会自己挡住：
            //    若订单此前已关闭，车票已被推进为 CANCELED，票务的 updateTicketStatus 会因
            //    "WHERE ticket_status = UNPAID" 影响 0 行而跳过座位更新，不会把座位标成已售。
            //    （这也与 Feign 路径的行为一致，保证两种模式可对等比较。）
            publishOrderPaid(payload.getOrderSn());

            meterRegistry.counter("my12306.mq.pay-success.consumed").increment();
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    /**
     * 装配订单侧入参。注意 payTime 必须带上：订单落库的 pay_time 就是从这里来的，
     * 缺了会用当前时间兜底，导致账实时间不一致。
     */
    private TicketOrderPayCallbackReqDTO buildPayCallbackReq(PaySuccessMessage payload) {
        TicketOrderPayCallbackReqDTO req = new TicketOrderPayCallbackReqDTO();
        req.setOrderSn(payload.getOrderSn());
        req.setPaySn(payload.getPaySn());
        req.setPayType(payload.getPayType());
        req.setPayTime(payload.getPayTime() == null ? new Date() : payload.getPayTime());
        return req;
    }

    /**
     * 发 ORDER_PAID。失败就抛出去让本方法进重试 ——
     * 重试时 payCallbackOrder 会因为幂等而变成空操作，只有这一步会真正重做，这是安全的。
     */
    private void publishOrderPaid(String orderSn) {
        DefaultMQProducer producer = producerProvider.getIfAvailable();
        if (producer == null) {
            throw new IllegalStateException("MQ 生产者不可用，无法发出 ORDER_PAID");
        }
        OrderPaidMessage message = OrderPaidMessage.builder().orderSn(orderSn).build();
        try {
            SendResult result = producer.send(new Message(PayNotifyMqConstants.TOPIC,
                    PayNotifyMqConstants.TAG_ORDER_PAID,
                    orderSn,
                    JSON.toJSONBytes(message)));
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("ORDER_PAID 未返回 SEND_OK：" + (result == null ? "null" : result.getSendStatus()));
            }
            log.info("已发出 ORDER_PAID，orderSn={}，msgId={}", orderSn, result.getMsgId());
        } catch (Exception ex) {
            throw new IllegalStateException("发出 ORDER_PAID 失败，orderSn=" + orderSn, ex);
        }
    }
}
