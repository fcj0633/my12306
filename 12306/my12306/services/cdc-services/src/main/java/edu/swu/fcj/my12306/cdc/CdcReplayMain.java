package edu.swu.fcj.my12306.cdc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit manual replay of reviewed DLQ bodies; never deletes the original DLQ record. */
public class CdcReplayMain {
    private static String required(String key) {
        String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Required environment: "+key);return value;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: CdcReplayMain <reviewed-V1-body.json>");
        String database=required("CDC_DATABASE"),topic=required("CDC_TOPIC"),namesrv=required("CDC_NAMESRV");
        byte[] body=Files.readAllBytes(Path.of(args[0]));
        if(body.length>EventEncoder.MAX_BYTES)throw new IllegalArgumentException("Body exceeds CDC limit");
        var event=new ObjectMapper().readValue(body,InventoryEvent.class);
        if(event.schemaVersion()!=1 || !database.equals(event.database()) || !"t_seat_segment".equals(event.table())
                || event.eventId()==null || event.eventId().isBlank() || event.rows()==null || event.rows().isEmpty()
                || event.rows().size()>100 || !java.util.Set.of("INSERT","UPDATE","DELETE").contains(event.operation()))
            throw new IllegalArgumentException("Replay is not a valid event for the explicitly selected source database");
        var producer=new DefaultMQProducer("inventory-cdc-manual-replay-"+ProcessHandle.current().pid());
        producer.setNamesrvAddr(namesrv);producer.setVipChannelEnabled(false);
        producer.setMaxMessageSize(EventEncoder.MAX_BYTES+4096);
        try {
            producer.start();
            if(producer.send(new Message(topic,"INVENTORY_V1",body)).getSendStatus()!=SendStatus.SEND_OK)
                throw new IllegalStateException("Replay was not confirmed; retain DLQ record");
            System.out.println("CDC_REPLAY_SEND_OK");
        } finally { producer.shutdown(); }
    }
}
