package edu.swu.fcj.my12306.biz.ticketservice.mq;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 死信兜底：盯着本服务消费者组对应的死信 topic。
 * <p>
 * 死信怎么来：消费方法抛异常 → 自动重试 16 次 → 仍失败 → 投进 {@code %DLQ%<消费者组>}。
 * 到这一步说明重试解决不了，需要人工介入。
 * <p>
 * ⚠️ 已知局限：消费会把消息从死信队列出队，所以这里只做到"计数 + 留痕"，不做重放。
 * 要重放得把死信持久化到表里，属明确不做项。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.pay.notify-mode", havingValue = "mq")
public class TicketDlqWatcher {

    private final MeterRegistry meterRegistry;

    @Value("${my12306.rocketmq.namesrv-addr:127.0.0.1:9876}")
    private String namesrvAddr;

    @Value("${my12306.rocketmq.ticket-consumer-group:my12306-pay-notify-ticket-cg}")
    private String ticketConsumerGroup;

    private DefaultMQPushConsumer dlqConsumer;

    @PostConstruct
    public void start() {
        String dlqTopic = "%DLQ%" + ticketConsumerGroup;
        try {
            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(ticketConsumerGroup + "-dlq-watcher");
            consumer.setNamesrvAddr(namesrvAddr);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            consumer.subscribe(dlqTopic, "*");
            // 必须显式声明成 MessageListenerConcurrently：DefaultMQPushConsumer 同时有
            // registerMessageListener(MessageListenerConcurrently) 与 (MessageListenerOrderly)，
            // 不写类型的话 lambda 对两者都能匹配，编译器会报"调用不明确"。
            consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    meterRegistry.counter("my12306.mq.dlq.count").increment();
                    log.error("票务侧收到死信！topic={}，msgId={}，重试次数={}，body={}",
                            msg.getTopic(), msg.getMsgId(), msg.getReconsumeTimes(),
                            new String(msg.getBody(), StandardCharsets.UTF_8));
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            this.dlqConsumer = consumer;
            log.info("票务侧死信监视消费者已启动，topic={}", dlqTopic);
        } catch (Throwable ex) {
            log.warn("票务侧死信监视消费者启动失败（死信 topic 可能尚不存在），不影响主流程：{}", ex.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (dlqConsumer != null) {
            dlqConsumer.shutdown();
        }
    }
}
