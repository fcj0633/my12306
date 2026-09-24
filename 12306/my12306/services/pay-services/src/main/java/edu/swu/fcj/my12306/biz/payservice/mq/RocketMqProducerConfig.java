package edu.swu.fcj.my12306.biz.payservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付服务侧的 MQ 生产者。
 * <p>
 * 【为什么不用 rocketmq-spring-boot-starter】
 * starter 2.2.3 只通过 {@code META-INF/spring.factories} 注册自动配置，而 Spring Boot 3 已移除
 * 这个机制（改为 {@code META-INF/spring/...AutoConfiguration.imports}）。用它的话
 * {@code RocketMQAutoConfiguration} 根本不会被加载，{@code RocketMQTemplate} 会静默不存在，
 * 排查成本很高。因此这里用原始 {@code rocketmq-client}，自己管生命周期 ——
 * 好处是与 Spring Boot 版本完全解耦。代价是要自己写启动/关闭。
 * <p>
 * ⚠️ 整个类都被 {@code @ConditionalOnProperty} 包住：只有 {@code my12306.pay.notify-mode=mq}
 * 时才创建连接，feign 模式下不会去连 broker（这既是回归保险，也是 A/B 压测的前提）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "my12306.pay.notify-mode", havingValue = "mq")
public class RocketMqProducerConfig {

    @Value("${my12306.rocketmq.namesrv-addr:127.0.0.1:9876}")
    private String namesrvAddr;

    @Value("${my12306.rocketmq.producer-group:my12306-pay-notify-producer}")
    private String producerGroup;

    /**
     * destroyMethod 交给 Spring 在容器关闭时调用 producer.shutdown()，避免进程退出时残留连接。
     */
    @Bean(destroyMethod = "shutdown")
    public DefaultMQProducer payNotifyProducer() throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(namesrvAddr);
        // 发送失败的重试次数。注意真正的可靠性不靠这个，而靠本地消息表 + 扫描任务重发。
        producer.setRetryTimesWhenSendFailed(2);
        producer.setSendMsgTimeout(3000);
        producer.start();
        log.info("RocketMQ 生产者已启动，group={}，namesrv={}", producerGroup, namesrvAddr);
        return producer;
    }
}
