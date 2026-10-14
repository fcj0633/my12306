package edu.swu.fcj.my12306.biz.ticketservice.mq;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="my12306.cdc.enabled",havingValue="true")
public class InventoryCdcConfig {
    @Bean(destroyMethod="shutdown") DefaultMQPushConsumer inventoryCdcPushConsumer(CacheInvalidationConsumer listener,
            @Value("${my12306.cdc.namesrv:${my12306.rocketmq.namesrv-addr:127.0.0.1:9876}}") String namesrv,
            @Value("${my12306.cdc.consumer-group:my12306-inventory-cache-cdc-cg}") String group,
            @Value("${my12306.cdc.topic:my12306-inventory-cache-cdc}") String topic) throws Exception {
        var consumer=new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(namesrv); consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeMessageBatchMaxSize(1); consumer.setConsumeThreadMin(4); consumer.setConsumeThreadMax(4);
        consumer.setMaxReconsumeTimes(16); consumer.subscribe(topic,"*");
        consumer.registerMessageListener(listener); consumer.start(); return consumer;
    }
}
