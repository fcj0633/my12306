package edu.swu.fcj.my12306.cdc;

import java.util.List;
import java.util.Map;

/** Internal V1 wire protocol. SQL NULL is a JSON null, never the string "null". */
public record InventoryEvent(int schemaVersion, String eventId, String source, String database,
        String table, String operation, String binlogFile, long binlogOffset,
        long sourceTimestamp, long publishedAt, List<Row> rows) {
    public record Row(Map<String, String> before, Map<String, String> after) { }
}
