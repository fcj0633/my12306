package edu.swu.fcj.my12306.biz.orderservice.mq;

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
 * 死信兜底：盯着本服务消费者组对应的死信 topic，把进入死信的消息计数并留下原文。
 * <p>
 * 死信是怎么来的：消费方法抛异常 → RocketMQ 自动重试（默认 16 次，指数退避）→ 仍失败
 * → 消息被投进 {@code %DLQ%<消费者组>}。能走到这里说明是"重试也解决不了"的问题
 * （例如消息体结构坏了、依赖的数据永久缺失），必须人工介入，不能靠自动重试。
 * <p>
 * 这里用【普通消费者】订阅死信 topic，而不是引入 rocketmq-tools 里的 DefaultMQAdminExt ——
 * 后者需要额外依赖，而本项目已经因为有本地消息表 + 补偿任务而不缺告警通道，够用即可。
 * <p>
 * ⚠️ 已知局限：消费会把消息从死信队列【出队】，所以这里只做到"计数 + 日志留痕"，
 * 不做重放。要支持重放得把死信持久化到表里，属明确不做项。
 * <p>
 * 用 @PostConstruct 而不是 @Bean 声明：死信 topic 在第一条死信出现前可能还不存在，
 * 启动失败不能拖垮整个应用，所以这里把异常吞掉降级为警告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.pay.notify-mode", havingValue = "mq")
public class OrderDlqWatcher {

    private final MeterRegistry meterRegistry;

    @Value("${my12306.rocketmq.namesrv-addr:127.0.0.1:9876}")
    private String namesrvAddr;

    @Value("${my12306.rocketmq.order-consumer-group:my12306-pay-notify-order-cg}")
    private String orderConsumerGroup;

    private DefaultMQPushConsumer dlqConsumer;

    @PostConstruct
    public void start() {
        String dlqTopic = "%DLQ%" + orderConsumerGroup;
        try {
            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(orderConsumerGroup + "-dlq-watcher");
            consumer.setNamesrvAddr(namesrvAddr);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            consumer.subscribe(dlqTopic, "*");
            // 必须显式声明成 MessageListenerConcurrently：DefaultMQPushConsumer 同时有
            // registerMessageListener(MessageListenerConcurrently) 与 (MessageListenerOrderly)，
            // 不写类型的话 lambda 对两者都能匹配，编译器会报"调用不明确"。
            consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    meterRegistry.counter("my12306.mq.dlq.count").increment();
                    log.error("订单侧收到死信！topic={}，msgId={}，重试次数={}，body={}",
                            msg.getTopic(), msg.getMsgId(), msg.getReconsumeTimes(),
                            new String(msg.getBody(), StandardCharsets.UTF_8));
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            this.dlqConsumer = consumer;
            log.info("订单侧死信监视消费者已启动，topic={}", dlqTopic);
        } catch (Throwable ex) {
            log.warn("订单侧死信监视消费者启动失败（死信 topic 可能尚不存在），不影响主流程：{}", ex.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (dlqConsumer != null) {
            dlqConsumer.shutdown();
        }
    }
}
