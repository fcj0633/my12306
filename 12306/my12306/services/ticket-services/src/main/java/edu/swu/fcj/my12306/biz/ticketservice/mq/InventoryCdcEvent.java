package edu.swu.fcj.my12306.biz.ticketservice.mq;
import java.util.List;
import java.util.Map;
/** V1 contract shared with the independent cdc-services through JSON only. */
public record InventoryCdcEvent(int schemaVersion, String eventId, String source, String database,
        String table, String operation, String binlogFile, long binlogOffset,
        long sourceTimestamp, long publishedAt, List<Row> rows) {
    public record Row(Map<String,String> before, Map<String,String> after) { }
}
