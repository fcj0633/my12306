package edu.swu.fcj.my12306.biz.ticketservice.mq;

import edu.swu.fcj.my12306.biz.ticketservice.common.PayNotifyMqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 票务侧的 MQ 装配：只需一个消费者（收 ORDER_PAID）。
 * <p>
 * 本服务【不发】任何消息 —— 这是链式事件驱动的末端：它只消费、不再往下游发。
 * （对比 order-services：既消费 PAY_SUCCESS，又生产 ORDER_PAID。）
 * <p>
 * 用原始 rocketmq-client 而非 starter，原因见根 pom 的 rocketmq.version 注释。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.pay.notify-mode", havingValue = "mq")
public class RocketMqConfig {

    private final PayResultTicketConsumer payResultTicketConsumer;

    @Value("${my12306.rocketmq.namesrv-addr:127.0.0.1:9876}")
    private String namesrvAddr;

    @Value("${my12306.rocketmq.ticket-consumer-group:my12306-pay-notify-ticket-cg}")
    private String ticketConsumerGroup;

    /**
     * 消费 ORDER_PAID 的推模式消费者。
     * <p>
     * 组名 my12306-pay-notify-ticket-cg 与订单侧的 -order-cg 不同：
     * 两组的订阅 tag 也不同（这里是 ORDER_PAID，那边是 PAY_SUCCESS），
     * 这就是"链式"而不是"并行扇出" —— 票务的消费以订单成功为前提。
     */
    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer payResultTicketPushConsumer() throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(ticketConsumerGroup);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(PayNotifyMqConstants.TOPIC, PayNotifyMqConstants.TAG_ORDER_PAID);
        consumer.registerMessageListener(payResultTicketConsumer);
        consumer.start();
        log.info("票务侧 MQ 消费者已启动，group={}，topic={}，tag={}",
                ticketConsumerGroup, PayNotifyMqConstants.TOPIC, PayNotifyMqConstants.TAG_ORDER_PAID);
        return consumer;
    }
}
