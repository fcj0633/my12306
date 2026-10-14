package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.*;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.*;
import edu.swu.fcj.my12306.biz.ticketservice.remote.*;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.PurchaseTicketTxService;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real InnoDB and Spring transaction proxies; deliberately bypasses distributed locks in race tests. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
        "spring.cloud.nacos.discovery.enabled=false","spring.cloud.discovery.enabled=false",
        "my12306.pay.notify-mode=feign","my12306.ticket.orphan-scan-enabled=false",
        "my12306.inventory.cache-events.enabled=false"})
class SegmentInventoryTest {
    private static final long TRAIN=990077;
    private static final String USER="segment-owned-test";
    @Autowired JdbcTemplate db;
    @Autowired PurchaseTicketTxService purchase;
    @Autowired TicketCallbackService callbacks;
    @Autowired edu.swu.fcj.my12306.biz.ticketservice.service.cache.InventoryCacheEventProcessor eventProcessor;
    @Autowired edu.swu.fcj.my12306.biz.ticketservice.service.cache.InventoryCacheEvents events;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @SpyBean SeatMapper seats;
    @MockBean RedisCacheHelper cache;
    @MockBean StringRedisTemplate redis;
    @MockBean RedissonClient redisson;
    @MockBean UserRemoteService users;
    @MockBean OrderRemoteService orders;
    @MockBean TicketAvailabilityTokenBucket tokens;

    @BeforeEach void fixture() {
        for(int i=0;i<4;i++) db.update("INSERT INTO t_inventory_station(train_id,station_index,station_name) VALUES(?,?,?)",TRAIN,i,"ABCD".substring(i,i+1));
        seat(99007701,2,"01","01A");
    }
    void seat(long id,int type,String carriage,String number) {
        db.update("INSERT INTO t_physical_seat(id,train_id,seat_type,carriage_number,seat_number) VALUES(?,?,?,?,?)",id,TRAIN,type,carriage,number);
        for(int i=0;i<3;i++) db.update("INSERT INTO t_seat_segment(seat_id,segment_index) VALUES(?,?)",id,i);
    }
    @AfterEach void cleanup() {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM t_inventory_cache_event WHERE train_id=?",Integer.class,TRAIN)));
        db.update("DELETE s FROM t_seat_segment s JOIN t_physical_seat p ON p.id=s.seat_id WHERE p.train_id=?",TRAIN);
        db.update("DELETE FROM t_physical_seat WHERE train_id=?",TRAIN);
        db.update("DELETE FROM t_inventory_station WHERE train_id=?",TRAIN);
        db.update("DELETE FROM t_inventory_cache_event WHERE train_id=?",TRAIN);
        db.update("DELETE FROM t_ticket WHERE username=?",USER);
    }
    PurchaseReservationResult reserve(String sn,String start,String end,int... types) {
        var request=new PurchaseTicketReqDTO();request.setTrainId(""+TRAIN);request.setDeparture(start);request.setArrival(end);
        var list=new ArrayList<PurchaseTicketPassengerDetailDTO>();var passengers=new LinkedHashMap<String,PassengerActualRespDTO>();
        for(int i=0;i<types.length;i++) {
            var passenger=new PurchaseTicketPassengerDetailDTO();passenger.setPassengerId(""+(900+i));passenger.setSeatType(types[i]);list.add(passenger);
            var actual=new PassengerActualRespDTO();actual.setId(passenger.getPassengerId());passengers.put(actual.getId(),actual);
        }
        request.setPassengers(list);
        return purchase.doPurchaseInTransaction(request,"1001",USER,sn,passengers,Map.of(),
                new PurchaseMetadata("TEST",Instant.EPOCH,Instant.EPOCH,Map.of(1,100,2,100)));
    }
    long occupied() {return db.queryForObject("SELECT COUNT(*) FROM t_seat_segment s JOIN t_physical_seat p ON p.id=s.seat_id WHERE p.train_id=? AND s.seat_status<>0",Long.class,TRAIN);}
    long ticketCount() {return db.queryForObject("SELECT COUNT(*) FROM t_ticket WHERE username=?",Long.class,USER);}
    long remaining(String start,String end) {
        return seats.countAvailableByType(TRAIN,start,end).stream().filter(x -> ((Number)x.get("seat_type")).intValue()==2)
                .mapToLong(x -> ((Number)x.get("cnt")).longValue()).sum();
    }

    @Test void overlapRejectedAndTouchingIntervalsReuseTheSeat() {
        var first=reserve("seg-first","A","C",2);
        assertThrows(ServiceException.class,() -> reserve("seg-overlap","B","D",2));
        var touching=reserve("seg-touch","C","D",2);
        assertEquals(first.ticketOrderDetails().getFirst().getSeatNumber(),touching.ticketOrderDetails().getFirst().getSeatNumber());
        assertEquals(3,occupied());assertEquals(2,ticketCount());
    }
    @Test void cancelOnlyReleasesOwnedSegmentsAndOldCallbacksCannotTouchNewOwner() {
        var ab=reserve("seg-ab","A","B",2);var bc=reserve("seg-bc","B","C",2);
        callbacks.cancelCallback(ab.compensationRequest());
        assertEquals(1,remaining("A","B"));assertEquals(0,remaining("A","C"));assertEquals(1,occupied());
        var replacement=reserve("seg-replacement","A","B",2);
        callbacks.cancelCallback(ab.compensationRequest());callbacks.payCallback(ab.compensationRequest());
        assertEquals(2,occupied());
        callbacks.payCallback(bc.compensationRequest());callbacks.payCallback(bc.compensationRequest());
        assertEquals(2,db.queryForObject("SELECT seat_status FROM t_seat_segment WHERE seat_id=99007701 AND segment_index=1",Integer.class));
        callbacks.cancelCallback(replacement.compensationRequest());assertEquals(1,occupied());
    }
    @Test void fragmentedSegmentsHaveNoContinuousSeat() {
        seat(99007702,2,"02","01A");
        var a=reserve("seg-frag-a","A","B",2);
        db.update("UPDATE t_seat_segment SET seat_status=1,ticket_id=123,order_sn='fragment-fixture' WHERE seat_id=99007702 AND segment_index=1");
        assertEquals(1,remaining("A","B"));assertEquals(1,remaining("B","C"));assertEquals(0,remaining("A","C"));
        assertThrows(ServiceException.class,() -> reserve("seg-frag-no","A","C",2));
        assertEquals(1,ticketCount());callbacks.cancelCallback(a.compensationRequest());
    }
    @Test void missingSegmentNeverMakesASeatAvailable() {
        db.update("DELETE FROM t_seat_segment WHERE seat_id=99007701 AND segment_index=1");
        assertEquals(0,remaining("A","C"));
        assertThrows(ServiceException.class,() -> reserve("seg-missing","A","C",2));assertEquals(0,ticketCount());
    }
    @Test void partialConditionalUpdateRollsBackTicketsAndEverySegment() {
        doAnswer(call -> db.update("UPDATE t_seat_segment SET seat_status=1,ticket_id=456,order_sn='partial-fixture' WHERE seat_id=99007701 AND segment_index=0"))
                .when(seats).reserveSegments(anyList(),anyInt(),anyInt(),anyString());
        assertThrows(ServiceException.class,() -> reserve("seg-partial","A","C",2));
        assertEquals(0,occupied());assertEquals(0,ticketCount());
    }
    @Test void incompleteCallbackRollsBackTicketClaimAndPartialRelease() {
        var r=reserve("seg-corrupt","A","C",2);
        db.update("DELETE FROM t_seat_segment WHERE seat_id=99007701 AND segment_index=1");
        assertThrows(ServiceException.class,() -> callbacks.cancelCallback(r.compensationRequest()));
        assertEquals(1,occupied());assertEquals(0,db.queryForObject("SELECT ticket_status FROM t_ticket WHERE order_sn='seg-corrupt'",Integer.class));
        verify(tokens,never()).invalidateOrThrow(TRAIN);
    }
    @Test void multiPassengerCrossCarriageAndMixedTypeTransactionsRemainAtomic() {
        seat(99007702,2,"02","01A");seat(99007703,1,"03","01A");
        var r=reserve("seg-multi","A","D",2,2,1);assertEquals(9,occupied());
        callbacks.cancelCallback(r.compensationRequest());assertEquals(0,occupied());
        assertThrows(ServiceException.class,() -> reserve("seg-no-partial","A","D",2,2,1,1));
        assertEquals(0,occupied());assertEquals(3,ticketCount());
    }
    @Test void twoConcurrentOverlappingPurchasesWithoutDistributedLocksHaveExactlyOneWinner() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            Callable<Boolean> ac=() -> {ready.countDown();start.await();try{reserve("seg-race-ac","A","C",2);return true;}catch(ServiceException ex){return false;}};
            Callable<Boolean> bd=() -> {ready.countDown();start.await();try{reserve("seg-race-bd","B","D",2);return true;}catch(ServiceException ex){return false;}};
            var a=pool.submit(ac);var b=pool.submit(bd);assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            assertEquals(1,(a.get(15,TimeUnit.SECONDS)?1:0)+(b.get(15,TimeUnit.SECONDS)?1:0));
            assertEquals(1,ticketCount());assertEquals(2,occupied());
        }
    }
    @Test void concurrentPaymentAndCancellationHaveOneConsistentTerminalState() throws Exception {
        var r=reserve("seg-callback-race","A","C",2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(() -> {start.await();return callbacks.payCallback(r.compensationRequest());});
            var b=pool.submit(() -> {start.await();return callbacks.cancelCallback(r.compensationRequest());});start.countDown();
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        }
        int status=db.queryForObject("SELECT ticket_status FROM t_ticket WHERE order_sn='seg-callback-race'",Integer.class);
        assertTrue(status==10 || status==30);assertEquals(status==10?2:0,occupied());
    }

    @Test void redisFailureKeepsDurableInvalidationAndRetryRemovesIt() {
        doThrow(new IllegalStateException("redis unavailable")).when(cache).invalidateRemaining(anyList());
        reserve("seg-event","A","C",2);
        assertEquals(2,occupied());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> verify(cache,atLeastOnce()).invalidateRemaining(anyList()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM t_inventory_cache_event WHERE train_id=?",Integer.class,TRAIN));
        reset(cache);
        var event=db.queryForObject("SELECT id,train_id,start_index,end_index,invalidate_bucket FROM t_inventory_cache_event WHERE train_id=?",(rs,n) -> {
            var e=new edu.swu.fcj.my12306.biz.ticketservice.dao.entity.InventoryCacheEventDO();
            e.setId(rs.getLong(1));e.setTrainId(rs.getLong(2));e.setStartIndex(rs.getInt(3));e.setEndIndex(rs.getInt(4));e.setInvalidateBucket(rs.getBoolean(5));return e;
        },TRAIN);
        eventProcessor.process(event);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM t_inventory_cache_event WHERE train_id=?",Integer.class,TRAIN));
        var keys=org.mockito.ArgumentCaptor.forClass(List.class);verify(cache).invalidateRemaining(keys.capture());
        assertEquals(5,keys.getValue().size()); // AB, AC, AD, BC, BD overlap AC; CD only touches.
    }
    @Test void rolledBackInventoryTransactionPublishesNoCacheEvent() {
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class,() -> transaction.execute(status -> {
            events.enqueue(TRAIN,new SegmentRange(0,2),false);throw new IllegalStateException("rollback");
        }));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM t_inventory_cache_event WHERE train_id=?",Integer.class,TRAIN));
        verify(cache,never()).invalidateRemaining(anyList());
    }
}
