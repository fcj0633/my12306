package edu.swu.fcj.my12306.cdc;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.Message;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.producer.SendStatus;

/** Checkpoint changes only after every synchronous send has confirmed SEND_OK. */
public class BatchForwarder {
    @FunctionalInterface public interface Sender { SendStatus send(byte[] body) throws Exception; }
    private final CanalConnector connector;
    private final EventEncoder encoder;
    private final Sender sender;
    private final MeterRegistry metrics;
    public BatchForwarder(CanalConnector connector, EventEncoder encoder, Sender sender, MeterRegistry metrics) {
        this.connector=connector; this.encoder=encoder; this.sender=sender; this.metrics=metrics;
    }
    public void forward(Message batch) throws Exception {
        if (batch.getId()==-1) return;
        try {
            for (var entry:batch.getEntries()) for (var body:encoder.encode(entry)) {
                if (sender.send(body)!=SendStatus.SEND_OK) throw new IllegalStateException("MQ did not confirm SEND_OK");
                metrics.counter("my12306.cdc.bridge.send.success").increment();
                org.slf4j.LoggerFactory.getLogger(getClass()).info("CDC_MQ_SENT bytes={}",body.length);
            }
            connector.ack(batch.getId());
            metrics.counter("my12306.cdc.bridge.ack").increment();
        } catch (Exception failure) {
            metrics.counter("my12306.cdc.bridge.send.failure").increment();
            try { connector.rollback(batch.getId()); metrics.counter("my12306.cdc.bridge.rollback").increment(); }
            catch (Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
}
