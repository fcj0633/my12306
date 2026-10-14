package edu.swu.fcj.my12306.cdc;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.producer.SendStatus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BatchForwarderTest {
    @Test void rocketMqHeadersEncodeWithCanalDependencies() {
        var header=new org.apache.rocketmq.remoting.protocol.header.namesrv.GetRouteInfoRequestHeader();header.setTopic("test");
        var command=org.apache.rocketmq.remoting.protocol.RemotingCommand.createRequestCommand(
                org.apache.rocketmq.remoting.protocol.RequestCode.GET_ROUTEINFO_BY_TOPIC,header);
        var bytes=io.netty.buffer.Unpooled.buffer();
        try {assertDoesNotThrow(()->command.fastEncodeHeader(bytes));assertTrue(bytes.readableBytes()>0);}
        finally {bytes.release();}
    }
    static CanalEntry.Column column(String name,String value) {
        var builder=CanalEntry.Column.newBuilder().setName(name).setIsNull(value==null);
        if(value!=null) builder.setValue(value);
        return builder.build();
    }
    static CanalEntry.Entry entry(int rows) {
        var image=CanalEntry.RowData.newBuilder().addAfterColumns(column("seat_id","1"))
                .addAfterColumns(column("segment_index","0")).addAfterColumns(column("seat_status","0"))
                .addAfterColumns(column("ticket_id",null)).addAfterColumns(column("order_sn",null)).build();
        var change=CanalEntry.RowChange.newBuilder().setEventType(CanalEntry.EventType.INSERT);
        for(int i=0;i<rows;i++) change.addRowDatas(image);
        return CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setSchemaName("fixture").setTableName("t_seat_segment")
                        .setLogfileName("mysql-bin.1").setLogfileOffset(101).setExecuteTime(1700000000000L))
                .setStoreValue(change.build().toByteString()).build();
    }
    @Test void allSendsMustSucceedBeforeAck() throws Exception {
        var connector=mock(CanalConnector.class); var sender=mock(BatchForwarder.Sender.class);
        when(sender.send(any())).thenReturn(SendStatus.SEND_OK);
        var forwarder=new BatchForwarder(connector,new EventEncoder(new ObjectMapper(),"source","fixture"),sender,new SimpleMeterRegistry());
        forwarder.forward(new Message(7,List.of(entry(101))));
        var sequence=inOrder(sender,connector); sequence.verify(sender,times(2)).send(any()); sequence.verify(connector).ack(7);
        verify(connector,never()).rollback(anyLong());
    }
    @Test void partialFailureRollsBackWithoutAck() throws Exception {
        var connector=mock(CanalConnector.class); var sender=mock(BatchForwarder.Sender.class);
        when(sender.send(any())).thenReturn(SendStatus.SEND_OK,SendStatus.FLUSH_DISK_TIMEOUT);
        var forwarder=new BatchForwarder(connector,new EventEncoder(new ObjectMapper(),"source","fixture"),sender,new SimpleMeterRegistry());
        assertThrows(Exception.class,()->forwarder.forward(new Message(7,List.of(entry(101)))));
        verify(connector,never()).ack(anyLong()); verify(connector).rollback(7);
    }
    @Test void exceptionRollsBackWithoutAck() throws Exception {
        var connector=mock(CanalConnector.class); var sender=mock(BatchForwarder.Sender.class);
        when(sender.send(any())).thenThrow(new IllegalStateException("network"));
        var forwarder=new BatchForwarder(connector,new EventEncoder(new ObjectMapper(),"source","fixture"),sender,new SimpleMeterRegistry());
        assertThrows(Exception.class,()->forwarder.forward(new Message(7,List.of(entry(1)))));
        verify(connector,never()).ack(anyLong()); verify(connector).rollback(7);
    }
    @Test void sqlNullAndStableEventId() throws Exception {
        var json=new ObjectMapper(); var encoder=new EventEncoder(json,"source","fixture");
        var a=encoder.encode(entry(101)); var b=encoder.encode(entry(101)); assertEquals(2,a.size());
        assertTrue(json.readTree(a.getFirst()).at("/rows/0/after/ticket_id").isNull());
        assertEquals(json.readTree(a.getFirst()).get("eventId"),json.readTree(b.getFirst()).get("eventId"));
        assertNotEquals(json.readTree(a.getFirst()).get("eventId"),json.readTree(a.getLast()).get("eventId"));
    }
    @Test void oversizedSingleRowRetainsCheckpoint() throws Exception {
        var original=entry(1);var change=CanalEntry.RowChange.parseFrom(original.getStoreValue());
        var row=change.getRowDatas(0).toBuilder().addAfterColumns(column("extra_payload","x".repeat(EventEncoder.MAX_BYTES))).build();
        var large=original.toBuilder().setStoreValue(change.toBuilder().clearRowDatas().addRowDatas(row).build().toByteString()).build();
        var connector=mock(CanalConnector.class);var sender=mock(BatchForwarder.Sender.class);
        var forwarder=new BatchForwarder(connector,new EventEncoder(new ObjectMapper(),"source","fixture"),sender,new SimpleMeterRegistry());
        assertThrows(Exception.class,()->forwarder.forward(new Message(7,List.of(large))));
        verify(connector,never()).ack(anyLong());verify(connector).rollback(7);verifyNoInteractions(sender);
    }
    @Test void malformedTargetDmlRetainsCheckpoint() throws Exception {
        var broken=entry(1).toBuilder().setStoreValue(CanalEntry.RowChange.newBuilder().setEventType(CanalEntry.EventType.UPDATE)
                .addRowDatas(CanalEntry.RowData.getDefaultInstance()).build().toByteString()).build();
        var connector=mock(CanalConnector.class);var sender=mock(BatchForwarder.Sender.class);
        var forwarder=new BatchForwarder(connector,new EventEncoder(new ObjectMapper(),"source","fixture"),sender,new SimpleMeterRegistry());
        assertThrows(Exception.class,()->forwarder.forward(new Message(7,List.of(broken))));
        verify(connector,never()).ack(anyLong());verify(connector).rollback(7);verifyNoInteractions(sender);
    }
}
