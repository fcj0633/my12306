package edu.swu.fcj.my12306.biz.orderservice.mq;

import edu.swu.fcj.my12306.biz.orderservice.common.PayNotifyMqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订单侧的 MQ 装配：一个消费者（收 PAY_SUCCESS）+ 一个生产者（发 ORDER_PAID）。
 * <p>
 * 用原始 rocketmq-client 而非 rocketmq-spring-boot-starter，原因见根 pom 的 rocketmq.version 注释
 * （starter 2.2.3 只通过 spring.factories 注册自动配置，Spring Boot 3 已移除该机制）。
 * <p>
 * 整类被 {@code @ConditionalOnProperty} 包住：只有 mq 模式才连 broker，feign 模式下这套东西完全不启动。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.pay.notify-mode", havingValue = "mq")
public class RocketMqConfig {

    private final PayResultOrderConsumer payResultOrderConsumer;

    @Value("${my12306.rocketmq.namesrv-addr:127.0.0.1:9876}")
    private String namesrvAddr;

    @Value("${my12306.rocketmq.order-consumer-group:my12306-pay-notify-order-cg}")
    private String orderConsumerGroup;

    @Value("${my12306.rocketmq.order-producer-group:my12306-order-notify-producer}")
    private String orderProducerGroup;

    /**
     * 消费 PAY_SUCCESS 的推模式消费者。
     * <p>
     * 消费者组名 my12306-pay-notify-order-cg 是"支付通知扇出到订单"的标识；
     * 它与票务侧的组名不同 —— 这正是"一条事件被多个独立消费者各自处理"的基础，
     * 也是支付服务不需要知道下游是谁的原因。
     * <p>
     * ConsumeFromWhere 用 FIRST_OFFSET：只在该组【还没有位点】时生效，
     * 这样即使消费者比生产者晚启动，期间积压的支付通知也能被消费到（这些消息不能丢）；
     * 之后会按 broker 上存的位点继续。
     */
    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer payResultOrderPushConsumer() throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(orderConsumerGroup);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(PayNotifyMqConstants.TOPIC, PayNotifyMqConstants.TAG_PAY_SUCCESS);
        consumer.registerMessageListener(payResultOrderConsumer);
        consumer.start();
        log.info("订单侧 MQ 消费者已启动，group={}，topic={}，tag={}",
                orderConsumerGroup, PayNotifyMqConstants.TOPIC, PayNotifyMqConstants.TAG_PAY_SUCCESS);
        return consumer;
    }

    /**
     * 发 ORDER_PAID 的生产者。
     */
    @Bean(destroyMethod = "shutdown")
    public DefaultMQProducer orderNotifyProducer() throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer(orderProducerGroup);
        producer.setNamesrvAddr(namesrvAddr);
        producer.setRetryTimesWhenSendFailed(2);
        producer.setSendMsgTimeout(3000);
        producer.start();
        log.info("订单侧 MQ 生产者已启动，group={}", orderProducerGroup);
        return producer;
    }
}
