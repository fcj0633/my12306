package edu.swu.fcj.my12306.biz.ticketservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TicketMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.*;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.*;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.benchmark.*;
import edu.swu.fcj.my12306.biz.ticketservice.service.cache.InventoryCacheEventProcessor;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.PurchaseTicketTxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Full transaction proxy timing, with independent candidate carriages and one passenger per call. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
        "my12306.pay.notify-mode=feign", "my12306.ticket.orphan-scan-enabled=false",
        "my12306.inventory.cache-events.enabled=false", "spring.datasource.hikari.maximum-pool-size=20"})
@Import(PurchaseTransactionPerformanceTest.BaselineConfig.class)
@EnabledIfSystemProperty(named="segment.benchmark", matches="true")
class PurchaseTransactionPerformanceTest {
    static final long TRAIN=990088, SEAT_BASE=990088000;
    static final int CONCURRENCY=50, SEATS_PER_CARRIAGE=24, CALLS_PER_WORKER=20;
    static final int BATCHES=10;
    static final String USER="purchase-tx-performance";
    @Autowired JdbcTemplate db;
    @Autowired DataSource dataSource;
    @Autowired PurchaseTicketTxService current;
    @Autowired LegacyPurchaseTicketTxService baseline;
    @MockBean RedisCacheHelper cache;
    @MockBean StringRedisTemplate redis;
    @MockBean RedissonClient redisson;
    @MockBean UserRemoteService users;
    @MockBean OrderRemoteService orders;
    @MockBean TicketAvailabilityTokenBucket tokens;
    // Persist the real outbox row and invoke the real transaction synchronization;
    // exclude asynchronous delivery/cleanup from this main-transaction-only benchmark.
    @MockBean InventoryCacheEventProcessor eventProcessor;
    final AtomicLong serial=new AtomicLong();

    @TestConfiguration
    @MapperScan(basePackageClasses=LegacySeatMapper.class)
    static class BaselineConfig {
        @Bean LegacySeatAllocator legacyAllocator(LegacySeatMapper mapper) {return new LegacySeatAllocator(mapper);}
        @Bean LegacyPurchaseTicketTxService legacyPurchase(LegacySeatMapper seats, TicketMapper tickets, LegacySeatAllocator allocator) {
            return new LegacyPurchaseTicketTxService(seats,tickets,allocator);
        }
    }

    @Test void compareFullTransactionAtFiftyConcurrentSingleTicketBuyers() throws Exception {
        try(var connection=dataSource.getConnection()) {
            assertTrue(connection.getCatalog().matches("codex_segment_test_[0-9]+"), "Requires task-owned isolated database");
        }
        var results=new ArrayList<Map<String,Object>>();
        try {
            fixture();
            try(var pool=Executors.newFixedThreadPool(CONCURRENCY)) {
                // Warm both code paths and all worker threads before collecting measurements.
                for(int warm=0;warm<2;warm++) for(int k:new int[]{1,2,4}) for(boolean modern:new boolean[]{false,true}) {
                    reset(); run(pool,modern,k,4,0);
                }
                for(int round=1;round<=3;round++) for(int k:new int[]{1,2,4}) {
                    for(boolean modern:round%2==1?new boolean[]{false,true}:new boolean[]{true,false}) {
                        var result=runGroup(pool,modern,k,round);
                        results.add(result);
                        var concise=new LinkedHashMap<>(result);concise.remove("samplesMs");
                        System.out.println("PURCHASE_TX_BENCHMARK "+new ObjectMapper().writeValueAsString(concise));
                    }
                }
            }
            var report=new LinkedHashMap<String,Object>();
            report.put("scope","Spring transaction proxy entry through return after commit; real allocator, ticket insert, inventory update, DTO work and durable outbox insert. Async event delivery mocked; external locks, HTTP, Redis admission and remote order excluded.");
            report.put("fixture","5 stations, 1200 physical seats, 50 independent candidate carriages, 24 seats each. Each batch starts empty and each worker buys 20 single tickets in its own carriage, without retries; occupancy rises to 20/24. Ten batches per arm/round; resets and reconciliation excluded from active-window throughput.");
            report.put("connectionPool",20);report.put("concurrency",CONCURRENCY);report.put("rounds",3);
            report.put("measuredCalls",results.size()*BATCHES*CONCURRENCY*CALLS_PER_WORKER);
            report.put("warmupCalls",12*CONCURRENCY*4);report.put("results",results);
            Path output=Path.of("target/segment-validation/purchase-transaction-performance.json");
            Files.createDirectories(output.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        } finally {cleanup();}
    }

    void fixture() {
        cleanup();
        for(int i=0;i<5;i++) db.update("INSERT INTO t_inventory_station VALUES(?,?,?)",TRAIN,i,"ABCDE".substring(i,i+1));
        var physical=new ArrayList<Object[]>();var segments=new ArrayList<Object[]>();var legacy=new ArrayList<Object[]>();
        long odId=SEAT_BASE*100;
        for(int worker=0;worker<CONCURRENCY;worker++) for(int s=0;s<SEATS_PER_CARRIAGE;s++) {
            long id=SEAT_BASE+worker*SEATS_PER_CARRIAGE+s;
            String carriage=String.format("%02d",worker+1),seat=String.format("%03dA",s+1);
            physical.add(new Object[]{id,TRAIN,2,carriage,seat});
            for(int i=0;i<4;i++) segments.add(new Object[]{id,i});
            for(int a=0;a<4;a++) for(int b=a+1;b<5;b++) legacy.add(new Object[]{odId++,TRAIN,carriage,seat,2,"ABCDE".substring(a,a+1),"ABCDE".substring(b,b+1),100});
        }
        db.batchUpdate("INSERT INTO t_physical_seat(id,train_id,seat_type,carriage_number,seat_number) VALUES(?,?,?,?,?)",physical);
        db.batchUpdate("INSERT INTO t_seat_segment(seat_id,segment_index) VALUES(?,?)",segments);
        db.batchUpdate("INSERT INTO t_seat(id,train_id,carriage_number,seat_number,seat_type,start_station,end_station,price,seat_status,del_flag,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,0,0,NOW(),NOW())",legacy);
    }
    void reset() {
        db.update("DELETE FROM t_ticket WHERE username=?",USER);
        db.update("DELETE FROM t_inventory_cache_event WHERE train_id=?",TRAIN);
        db.update("UPDATE t_seat SET seat_status=0 WHERE train_id=?",TRAIN);
        db.update("UPDATE t_seat_segment SET seat_status=0,ticket_id=NULL,order_sn=NULL WHERE seat_id>=? AND seat_id<?",SEAT_BASE,SEAT_BASE+CONCURRENCY*SEATS_PER_CARRIAGE);
    }
    void cleanup() {
        db.update("DELETE FROM t_ticket WHERE username=?",USER);
        db.update("DELETE FROM t_inventory_cache_event WHERE train_id=?",TRAIN);
        db.update("DELETE s FROM t_seat_segment s JOIN t_physical_seat p ON p.id=s.seat_id WHERE p.train_id=?",TRAIN);
        db.update("DELETE FROM t_physical_seat WHERE train_id=?",TRAIN);
        db.update("DELETE FROM t_inventory_station WHERE train_id=?",TRAIN);
        db.update("DELETE FROM t_seat WHERE train_id=?",TRAIN);
    }

    Map<String,Object> runGroup(ExecutorService pool,boolean modern,int k,int round) throws Exception {
        var samples=new ArrayList<Double>();var batches=new ArrayList<Map<String,Object>>();double elapsed=0;
        for(int batch=0;batch<BATCHES;batch++) {
            reset();
            var result=run(pool,modern,k,CALLS_PER_WORKER,round);
            verifyOwnership(modern,k,CONCURRENCY*CALLS_PER_WORKER);
            @SuppressWarnings("unchecked") var values=(List<Double>)result.get("samplesMs");
            samples.addAll(values);elapsed+=((Number)result.get("elapsedSeconds")).doubleValue();
            var concise=new LinkedHashMap<>(result);concise.remove("samplesMs");batches.add(concise);
        }
        Collections.sort(samples);
        var result=statistics(modern,k,round,samples,elapsed);
        result.put("batches",batches);return result;
    }

    Map<String,Object> run(ExecutorService pool,boolean modern,int k,int calls,int round) throws Exception {
        var ready=new CountDownLatch(CONCURRENCY);var start=new CountDownLatch(1);
        var futures=new ArrayList<Future<List<Double>>>();
        var passenger=new PurchaseTicketPassengerDetailDTO();passenger.setPassengerId("900");passenger.setSeatType(2);
        var actual=new PassengerActualRespDTO();actual.setId("900");
        var metadata=new PurchaseMetadata("BENCH",Instant.EPOCH,Instant.EPOCH,Map.of(2,100));
        for(int worker=0;worker<CONCURRENCY;worker++) {
            final String carriage=String.format("%02d",worker+1);
            futures.add(pool.submit(() -> {
                var samples=new ArrayList<Double>();
                var request=new PurchaseTicketReqDTO();request.setTrainId(""+TRAIN);request.setDeparture("A");
                request.setArrival("ABCDE".substring(k,k+1));request.setPassengers(List.of(passenger));
                var candidates=Map.of(2,carriage);var passengers=Map.of("900",actual);
                ready.countDown();start.await();
                for(int i=0;i<calls;i++) {
                    String sn="tx-perf-"+serial.incrementAndGet();
                    long before=System.nanoTime();
                    var result=modern?current.doPurchaseInTransaction(request,"1001",USER,sn,passengers,candidates,metadata)
                            :baseline.doPurchaseInTransaction(request,"1001",USER,sn,passengers,candidates,metadata);
                    samples.add((System.nanoTime()-before)/1_000_000d);
                    assertEquals(1,result.ticketOrderDetails().size());
                }
                return samples;
            }));
        }
        assertTrue(ready.await(15,TimeUnit.SECONDS));
        long before=System.nanoTime();start.countDown();
        var values=new ArrayList<Double>();
        for(var future:futures) values.addAll(future.get(120,TimeUnit.SECONDS));
        double elapsed=(System.nanoTime()-before)/1_000_000_000d;
        Collections.sort(values);
        return statistics(modern,k,round,values,elapsed);
    }
    Map<String,Object> statistics(boolean modern,int k,int round,List<Double> values,double elapsed) {
        var result=new LinkedHashMap<String,Object>();
        result.put("round",round);result.put("model",modern?"segment":"legacy-od");result.put("segments",k);
        result.put("success",values.size());result.put("failure",0);result.put("elapsedSeconds",elapsed);
        result.put("successfulTransactionsPerSecond",values.size()/elapsed);
        result.put("meanMs",values.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        result.put("p50Ms",percentile(values,.50));result.put("p95Ms",percentile(values,.95));result.put("p99Ms",percentile(values,.99));
        result.put("minMs",values.getFirst());result.put("maxMs",values.getLast());result.put("samplesMs",values);
        return result;
    }
    static double percentile(List<Double> values,double percentile) {return values.get((int)Math.ceil(values.size()*percentile)-1);}
    void verifyOwnership(boolean modern,int k,int count) {
        assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM t_ticket WHERE username=?",Integer.class,USER));
        if(modern) {
            assertEquals(count*k,db.queryForObject("SELECT COUNT(*) FROM t_seat_segment WHERE seat_id>=? AND seat_id<? AND seat_status=1",Integer.class,SEAT_BASE,SEAT_BASE+CONCURRENCY*SEATS_PER_CARRIAGE));
            assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM t_inventory_cache_event WHERE train_id=?",Integer.class,TRAIN));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM t_seat_segment s LEFT JOIN t_ticket t ON t.id=s.ticket_id AND t.order_sn=s.order_sn WHERE s.seat_id>=? AND s.seat_id<? AND s.seat_status=1 AND t.id IS NULL",Integer.class,SEAT_BASE,SEAT_BASE+CONCURRENCY*SEATS_PER_CARRIAGE));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM (SELECT carriage_number,seat_number,COUNT(*) n FROM t_ticket WHERE username=? GROUP BY carriage_number,seat_number HAVING n<>1) q",Integer.class,USER));
        } else assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM t_seat WHERE train_id=? AND seat_status=1",Integer.class,TRAIN));
    }
}
