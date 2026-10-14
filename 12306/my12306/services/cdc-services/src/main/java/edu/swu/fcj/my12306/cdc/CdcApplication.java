package edu.swu.fcj.my12306.cdc;

import com.alibaba.otter.canal.client.CanalConnectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.slf4j.LoggerFactory;
import java.net.InetSocketAddress;

@SpringBootApplication
public class CdcApplication {
    public static void main(String[] args) { SpringApplication.run(CdcApplication.class,args); }
    @Bean ApplicationRunner bridge(Environment env, MeterRegistry metrics) {
        return args -> {
            String database=env.getRequiredProperty("cdc.database");
            if (!database.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("CDC_DATABASE must be explicitly configured");
            var producer=new DefaultMQProducer(env.getRequiredProperty("cdc.producer-group"));
            producer.setNamesrvAddr(env.getRequiredProperty("cdc.namesrv")); producer.setVipChannelEnabled(false);
            producer.setMaxMessageSize(EventEncoder.MAX_BYTES+4096); producer.setSendMsgTimeout(5000);
            producer.start();
            var connector=CanalConnectors.newSingleConnector(new InetSocketAddress(env.getRequiredProperty("cdc.canal-host"),
                    env.getProperty("cdc.canal-port",Integer.class,11111)),env.getRequiredProperty("cdc.destination"),
                    env.getProperty("cdc.canal-user",""),env.getProperty("cdc.canal-password",""));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { connector.disconnect(); } finally { producer.shutdown(); } }));
            var encoder=new EventEncoder(new ObjectMapper(),env.getRequiredProperty("cdc.source"),database);
            var forwarder=new BatchForwarder(connector,encoder,body -> producer.send(new org.apache.rocketmq.common.message.Message(
                    env.getRequiredProperty("cdc.topic"),"INVENTORY_V1",body)).getSendStatus(),metrics);
            int backoff=1000;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Canal uses Apache ORO, which does not support Java Pattern.quote's \Q...\E.
                    // Database is already restricted to letters, digits and underscore.
                    connector.connect(); connector.subscribe(database+"\\.t_seat_segment"); connector.rollback();
                    LoggerFactory.getLogger(CdcApplication.class).info("CDC_BRIDGE_READY database={}",database);
                    while (!Thread.currentThread().isInterrupted()) {
                        var batch=connector.getWithoutAck(50);
                        forwarder.forward(batch);
                        backoff=1000;
                        if (batch.getId()==-1 || batch.getEntries().isEmpty()) Thread.sleep(100);
                    }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (Exception ex) {
                    LoggerFactory.getLogger(CdcApplication.class).error("CDC forwarding failed; checkpoint retained, retry in {} ms",backoff,ex);
                    try { Thread.sleep(backoff); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    backoff=Math.min(30000,backoff*2);
                } finally { try { connector.disconnect(); } catch (Exception ignored) { } }
            }
            producer.shutdown();
        };
    }
}
