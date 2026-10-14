package edu.swu.fcj.my12306.biz.ticketservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Paired SQL experiment only: excludes HTTP, remote order calls, distributed locks and cache invalidation. */
@EnabledIfSystemProperty(named="segment.benchmark",matches="true")
@EnabledIfEnvironmentVariable(named="TICKET_DB_URL",matches=".*codex_segment_test_.*")
class SegmentPerformanceTest {
    static final String LEGACY="""
            SELECT id,carriage_number,seat_number FROM t_seat
            WHERE train_id=1 AND seat_type=2 AND seat_status=0 AND start_station=? AND end_station=? AND del_flag=0
            ORDER BY carriage_number,seat_number LIMIT ?
            """;
    static final String SEGMENT="SELECT p.id,p.carriage_number,p.seat_number FROM t_physical_seat p "
            +SeatMapper.RANGE_JOIN+" WHERE p.train_id=1 AND p.seat_type=2 AND p.del_flag=0 "
            +SeatMapper.FULLY_AVAILABLE+" ORDER BY p.carriage_number,p.seat_number LIMIT ?";
    record Work(String arm,String start,String end,int length,int passengers,int round,int concurrency,String state) { }
    record Seat(long id,String carriage,String number) { }
    record Plan(List<Long> physical,List<Long> legacy) { }

    @Test void pairedQueryAndInventoryWriteExperiment() throws Exception {
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TICKET_DB_URL"));config.setUsername(System.getenv("TICKET_DB_USER"));
        config.setPassword(System.getenv("TICKET_DB_PASSWORD"));config.setMaximumPoolSize(20);config.setMinimumIdle(20);
        var results=new ArrayList<Map<String,Object>>();var explain=new LinkedHashMap<String,Object>();
        var fixtureCounts=new LinkedHashMap<String,Object>();
        try(var pool=new HikariDataSource(config)) {
            var db=new JdbcTemplate(pool);
            assertTrue(db.queryForObject("SELECT DATABASE()",String.class).matches("codex_segment_test_[0-9]+"));
            var stations=db.queryForList("SELECT station_name FROM t_inventory_station WHERE train_id=1 ORDER BY station_index",String.class);
            assertEquals(5,stations.size());
            try {
                for(String state:List.of("low","high","fragmented")) {
                    prepareState(db,state);
                    fixtureCounts.put(state,db.queryForList("""
                            SELECT s.segment_index,COUNT(*) AS free_seats FROM t_seat_segment s
                            JOIN t_physical_seat p ON p.id=s.seat_id
                            WHERE p.train_id=1 AND p.seat_type=2 AND s.seat_status=0 GROUP BY s.segment_index
                            """));
                    if(state.equals("high")) assertEquals(81,db.queryForObject("SELECT COUNT(*) FROM t_seat_segment s JOIN t_physical_seat p ON p.id=s.seat_id WHERE p.train_id=1 AND p.seat_type=2 AND s.segment_index=0 AND s.seat_status=0",Integer.class));
                    for(int k:new int[]{1,2,4}) for(int passengers:new int[]{1,5}) {
                        String start=stations.getFirst(),end=stations.get(k);
                        assertEquals(queryIds(pool,"baseline",start,end,passengers).size(),queryIds(pool,"segment",start,end,passengers).size());
                        if(passengers==1) for(String arm:List.of("baseline","segment")) {
                            String sql=(arm.equals("baseline")?LEGACY:segmentSql());
                            explain.put(state+"/"+k+"/"+arm,db.queryForList("EXPLAIN ANALYZE "+sql,start,end,passengers));
                        }
                        for(int concurrency:new int[]{20,50,100}) try(var workers=Executors.newFixedThreadPool(concurrency)) {
                            for(int round=0;round<3;round++) for(String arm:round%2==0?List.of("baseline","segment"):List.of("segment","baseline")) {
                                Work work=new Work(arm,start,end,k,passengers,round,concurrency,state);
                                results.add(readRound(pool,workers,work));
                            }
                        }
                    }
                }
                prepareState(db,"low");
                for(int k:new int[]{1,2,4}) for(int passengers:new int[]{1,5}) for(int concurrency:new int[]{20,50,100}) {
                    var physical=db.query("SELECT id,carriage_number,seat_number FROM t_physical_seat WHERE train_id=1 AND seat_type=2 ORDER BY carriage_number,seat_number LIMIT ?",
                            (rs,n) -> new Seat(rs.getLong(1),rs.getString(2),rs.getString(3)),passengers*concurrency);
                    assertEquals(passengers*concurrency,physical.size());
                    var plans=new ArrayList<Plan>();String start=stations.getFirst(),end=stations.get(k);
                    for(int i=0;i<concurrency;i++) {
                        var subset=physical.subList(i*passengers,(i+1)*passengers);var old=new ArrayList<Long>();
                        for(var seat:subset) old.add(db.queryForObject("SELECT id FROM t_seat WHERE train_id=1 AND seat_type=2 AND carriage_number=? AND seat_number=? AND start_station=? AND end_station=? AND del_flag=0",Long.class,seat.carriage(),seat.number(),start,end));
                        plans.add(new Plan(subset.stream().map(Seat::id).toList(),old));
                    }
                    try(var workers=Executors.newFixedThreadPool(concurrency)) {
                        for(int round=0;round<3;round++) for(String arm:round%2==0?List.of("baseline","segment"):List.of("segment","baseline"))
                            results.add(writeRound(pool,workers,new Work(arm,start,end,k,passengers,round,concurrency,"low"),plans));
                    }
                }
            } finally {prepareState(db,"low");}
        }
        var report=new LinkedHashMap<String,Object>();report.put("scope","SQL query and inventory-only transaction experiment; not end-to-end purchase QPS");
        report.put("poolSize",20);report.put("rounds",3);report.put("queryRepeatsPerWorker",3);
        report.put("writeRepeatsPerWorker",2);report.put("writeCleanupExcludedFromLatency",true);
        report.put("writeIncludes",List.of("connection acquisition","selected-seat availability validation","conditional inventory update","commit"));
        report.put("writeExcludes",List.of("ticket insert","outbox insert/processing","Redis/Lua","carriage/user locks","HTTP","remote calls"));
        report.put("results",results);report.put("explainAnalyze",explain);report.put("fixtureFreeCounts",fixtureCounts);
        var output=Path.of("target/segment-validation/performance.json");Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
    }

    static String segmentSql() {return SEGMENT.replace("#{departure}","?").replace("#{arrival}","?");}
    static List<Long> queryIds(HikariDataSource pool,String arm,String start,String end,int count) throws SQLException {
        try(var c=pool.getConnection();var p=c.prepareStatement(arm.equals("baseline")?LEGACY:segmentSql())) {
            p.setString(1,start);p.setString(2,end);p.setInt(3,count);var ids=new ArrayList<Long>();
            try(var rs=p.executeQuery()) {while(rs.next()) ids.add(rs.getLong(1));}return ids;
        }
    }
    static void prepareState(JdbcTemplate db,String state) {
        db.update("UPDATE t_seat_segment s JOIN t_physical_seat p ON p.id=s.seat_id SET s.seat_status=0,s.ticket_id=NULL,s.order_sn=NULL WHERE p.train_id=1");
        // Snowflake ids are not uniformly distributed modulo small integers. Use seat ordinals instead.
        if(!state.equals("low")) db.update("""
                UPDATE t_seat_segment s JOIN (
                  SELECT id,ROW_NUMBER() OVER(PARTITION BY seat_type ORDER BY carriage_number,seat_number)-1 AS ordinal
                  FROM t_physical_seat WHERE train_id=1
                ) p ON p.id=s.seat_id SET s.seat_status=1,s.ticket_id=999,s.order_sn='benchmark-fixture' WHERE
                """+(state.equals("high")?"MOD(p.ordinal,10)<9":"MOD(p.ordinal,4)=s.segment_index"));
        // Baseline OD availability is derived from the SAME segment state, including fragmentation.
        db.update("""
                UPDATE t_seat o JOIN t_physical_seat p ON p.train_id=o.train_id AND p.seat_type=o.seat_type
                AND p.carriage_number=o.carriage_number AND p.seat_number=o.seat_number
                JOIN t_inventory_station d ON d.train_id=o.train_id AND d.station_name=o.start_station
                JOIN t_inventory_station a ON a.train_id=o.train_id AND a.station_name=o.end_station
                SET o.seat_status=IF((SELECT COUNT(*) FROM t_seat_segment s WHERE s.seat_id=p.id
                AND s.segment_index BETWEEN d.station_index AND a.station_index-1 AND s.seat_status=0)=a.station_index-d.station_index,0,1)
                WHERE o.train_id=1
                """);
    }
    static Map<String,Object> readRound(HikariDataSource pool,ExecutorService workers,Work w) throws Exception {
        // Warm both connection acquisition and the query before timed work in every arm.
        for(int i=0;i<20;i++) queryIds(pool,w.arm(),w.start(),w.end(),w.passengers());
        var ready=new CountDownLatch(w.concurrency());var start=new CountDownLatch(1);
        var futures=new ArrayList<Future<List<Long>>>();
        for(int i=0;i<w.concurrency();i++) futures.add(workers.submit(() -> {
            ready.countDown();start.await();var values=new ArrayList<Long>();
            for(int j=0;j<3;j++) {long before=System.nanoTime();queryIds(pool,w.arm(),w.start(),w.end(),w.passengers());values.add(System.nanoTime()-before);}return values;
        }));
        assertTrue(ready.await(10,TimeUnit.SECONDS));long before=System.nanoTime();start.countDown();var samples=new ArrayList<Long>();
        for(var f:futures) samples.addAll(f.get(60,TimeUnit.SECONDS));long duration=System.nanoTime()-before;
        var result=summary(w,"query",samples);result.put("queryQps",samples.size()*1e9/duration);return result;
    }
    static Map<String,Object> writeRound(HikariDataSource pool,ExecutorService workers,Work w,List<Plan> plans) throws Exception {
        var ready=new CountDownLatch(w.concurrency());var start=new CountDownLatch(1);var futures=new ArrayList<Future<List<Long>>>();
        for(var plan:plans) futures.add(workers.submit(() -> {
            ready.countDown();start.await();var samples=new ArrayList<Long>();
            for(int i=0;i<2;i++) {
                long before=System.nanoTime();
                try(var c=pool.getConnection()) {
                    c.setAutoCommit(false);
                    try {
                        String ids=placeholders(plan.physical().size());
                        if(w.arm().equals("baseline")) {
                            try(var p=c.prepareStatement("SELECT COUNT(*) FROM t_seat WHERE seat_status=0 AND id IN ("+ids+")")) {
                                bind(p,plan.legacy(),1);try(var rs=p.executeQuery()){rs.next();assertEquals(w.passengers(),rs.getInt(1));}
                            }
                            try(var p=c.prepareStatement("UPDATE t_seat SET seat_status=1 WHERE seat_status=0 AND id IN ("+ids+") ORDER BY id")) {
                                bind(p,plan.legacy(),1);assertEquals(w.passengers(),p.executeUpdate());
                            }
                        } else {
                            try(var p=c.prepareStatement("SELECT COUNT(*) FROM t_seat_segment WHERE seat_status=0 AND segment_index BETWEEN 0 AND ? AND seat_id IN ("+ids+")")) {
                                p.setInt(1,w.length()-1);bind(p,plan.physical(),2);try(var rs=p.executeQuery()){rs.next();assertEquals(w.passengers()*w.length(),rs.getInt(1));}
                            }
                            try(var p=c.prepareStatement("UPDATE t_seat_segment SET seat_status=1,ticket_id=999,order_sn='benchmark-write' WHERE seat_status=0 AND ticket_id IS NULL AND segment_index BETWEEN 0 AND ? AND seat_id IN ("+ids+") ORDER BY seat_id,segment_index")) {
                                p.setInt(1,w.length()-1);bind(p,plan.physical(),2);assertEquals(w.passengers()*w.length(),p.executeUpdate());
                            }
                        }
                        c.commit();samples.add(System.nanoTime()-before);
                    } catch(Throwable ex) {c.rollback();throw ex;}
                }
                // Fixture restoration is outside the latency sample and is never counted as purchase throughput.
                try(var c=pool.getConnection();var p=c.prepareStatement(w.arm().equals("baseline")
                        ?"UPDATE t_seat SET seat_status=0 WHERE id IN ("+placeholders(plan.legacy().size())+")"
                        :"UPDATE t_seat_segment SET seat_status=0,ticket_id=NULL,order_sn=NULL WHERE order_sn='benchmark-write' AND seat_id IN ("+placeholders(plan.physical().size())+")")) {
                    bind(p,w.arm().equals("baseline")?plan.legacy():plan.physical(),1);p.executeUpdate();
                }
            }return samples;
        }));
        assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();var samples=new ArrayList<Long>();
        for(var f:futures) samples.addAll(f.get(60,TimeUnit.SECONDS));var result=summary(w,"inventoryTransaction",samples);
        result.put("updatedRowsPerRequest",w.passengers()*(w.arm().equals("segment")?w.length():1));return result;
    }
    static Map<String,Object> summary(Work w,String kind,List<Long> samples) {
        Collections.sort(samples);var result=new LinkedHashMap<String,Object>();
        result.put("kind",kind);result.put("arm",w.arm());result.put("state",w.state());result.put("segments",w.length());
        result.put("passengers",w.passengers());result.put("concurrency",w.concurrency());result.put("round",w.round());
        result.put("samples",samples.size());result.put("meanMs",samples.stream().mapToLong(Long::longValue).average().orElseThrow()/1e6);
        result.put("p95Ms",samples.get((int)Math.ceil(samples.size()*.95)-1)/1e6);result.put("p99Ms",samples.get((int)Math.ceil(samples.size()*.99)-1)/1e6);return result;
    }
    static String placeholders(int count) {return String.join(",",Collections.nCopies(count,"?"));}
    static void bind(PreparedStatement statement,List<Long> ids,int offset) throws SQLException {for(int i=0;i<ids.size();i++) statement.setLong(offset+i,ids.get(i));}
}
