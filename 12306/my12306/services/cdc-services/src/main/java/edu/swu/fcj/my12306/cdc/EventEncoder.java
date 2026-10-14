package edu.swu.fcj.my12306.cdc;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class EventEncoder {
    public static final int MAX_BYTES = 256 * 1024;
    private final ObjectMapper json;
    private final String source, database;
    public EventEncoder(ObjectMapper json, String source, String database) {
        if (source==null || source.isBlank() || database==null || !database.matches("[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Explicit CDC source/database required");
        this.json=json; this.source=source; this.database=database;
    }
    public List<byte[]> encode(CanalEntry.Entry entry) throws Exception {
        var h=entry.getHeader();
        if (entry.getEntryType()!=CanalEntry.EntryType.ROWDATA || !database.equals(h.getSchemaName())
                || !"t_seat_segment".equals(h.getTableName())) return List.of();
        var change=CanalEntry.RowChange.parseFrom(entry.getStoreValue());
        if (change.getIsDdl()) throw new IllegalArgumentException("Target inventory DDL requires a stopped migration");
        var op=change.getEventType();
        if (op!=CanalEntry.EventType.INSERT && op!=CanalEntry.EventType.UPDATE && op!=CanalEntry.EventType.DELETE)
            throw new IllegalArgumentException("Unsupported target DML: "+op);
        var rows=new ArrayList<InventoryEvent.Row>();
        for (var row:change.getRowDatasList()) {
            var before=columns(row.getBeforeColumnsList()); var after=columns(row.getAfterColumnsList());
            if ((op!=CanalEntry.EventType.INSERT && before.isEmpty()) || (op!=CanalEntry.EventType.DELETE && after.isEmpty())
                    || (op==CanalEntry.EventType.INSERT && !before.isEmpty()) || (op==CanalEntry.EventType.DELETE && !after.isEmpty()))
                throw new IllegalArgumentException("Invalid DML row images");
            rows.add(new InventoryEvent.Row(before,after));
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Target DML without rows");
        var messages=new ArrayList<byte[]>();
        int from=0, shard=0;
        long published=System.currentTimeMillis();
        while (from<rows.size()) {
            int to=Math.min(from+100,rows.size());
            byte[] bytes;
            while (true) {
                String position=source+"|"+database+"|"+h.getLogfileName()+"|"+h.getLogfileOffset()+"|"+shard;
                String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(position.getBytes(StandardCharsets.UTF_8)));
                bytes=json.writeValueAsBytes(new InventoryEvent(1,id,source,database,"t_seat_segment",op.name(),
                        h.getLogfileName(),h.getLogfileOffset(),h.getExecuteTime(),published,List.copyOf(rows.subList(from,to))));
                if (bytes.length<=MAX_BYTES) break;
                if (to==from+1) throw new IllegalArgumentException("Single CDC row exceeds 256 KiB; checkpoint retained");
                to=from+(to-from)/2;
            }
            messages.add(bytes); from=to; shard++;
        }
        return messages;
    }
    static Map<String,String> columns(List<CanalEntry.Column> columns) {
        Map<String,String> values=new LinkedHashMap<>();
        for (var column:columns) values.put(column.getName(),column.getIsNull()?null:column.getValue());
        if (!values.isEmpty()) for (String field:List.of("seat_id","segment_index","seat_status","ticket_id","order_sn"))
            if (!values.containsKey(field)) throw new IllegalArgumentException("ROW/FULL inventory field missing: "+field);
        return values;
    }
}
