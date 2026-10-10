package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.common.*;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketOrderDetailRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.remote.*;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.*;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat.CarriageDirectory;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.junit.jupiter.api.*;
import org.redisson.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transaction proxy and MySQL, isolated coordinates removed in finally; remote dependencies mocked. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
        "my12306.pay.notify-mode=feign", "my12306.ticket.orphan-scan-enabled=false"})
class CarriageReservationTest {
    @Autowired JdbcTemplate db;
    @Autowired PurchaseTicketService purchase;
    @MockBean RedissonClient redisson;
    @MockBean RedisCacheHelper cache;
    @MockBean UserRemoteService users;
    @MockBean OrderRemoteService orders;
    @MockBean TicketAvailabilityTokenBucket tokens;
    @MockBean CarriageDirectory directory;
    @SpyBean SeatMapper seatMapper;
    final String username="carriage-isolated-test";
    final List<Long> ids=new ArrayList<>();

    @BeforeEach void setup() {
        UserContext.setUser(UserInfoDTO.builder().userId("1001").username(username).build());
        when(directory.carriages(1L,2)).thenReturn(List.of("TEST-C1","TEST-C2","TEST-C3"));
        when(directory.carriages(1L,1)).thenReturn(List.of("TEST-C1","TEST-C2","TEST-C3"));
        // Restrict global fallback reads to this fixture; real allocation SQL is exercised by the
        // existing allocator tests and runtime tests. Never let fixtures consume the original pool.
        doAnswer(call -> {
            String after=call.getArgument(4);int count=call.getArgument(5);Integer type=call.getArgument(3);
            return db.query("SELECT id,carriage_number,seat_number FROM t_seat WHERE carriage_number LIKE 'TEST-C%' "
                    +"AND train_id=1 AND seat_type=? AND seat_status=0 AND del_flag=0 AND carriage_number>? "
                    +"ORDER BY carriage_number,seat_number LIMIT ?",(rs,n)->{
                        SeatDO s=new SeatDO();s.setId(rs.getLong(1));s.setCarriageNumber(rs.getString(2));s.setSeatNumber(rs.getString(3));return s;
                    },type,after==null?"":after,count);
        }).when(seatMapper).selectAvailableSeatCandidates(anyLong(),anyString(),anyString(),anyInt(),nullable(String.class),anyInt());
        when(cache.safeGet(anyString(), org.mockito.ArgumentMatchers.<Supplier<String>>any(),anyLong(),any(TimeUnit.class)))
                .thenAnswer(call -> ((Supplier<String>)call.getArgument(1)).get());
        RLock lock=mock(RLock.class);when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(tokens.takeToken(anyLong(),anyString(),anyString(),anyMap())).thenReturn(true);
        when(users.listPassengerQueryByIds(anyString(),any())).thenAnswer(call -> {
            List<Long> passengerIds=call.getArgument(1);
            return new Result<List<PassengerActualRespDTO>>().setCode(Result.SUCCESS_CODE).setData(passengerIds.stream().map(id -> {
                PassengerActualRespDTO p=new PassengerActualRespDTO();p.setId(id.toString());return p;
            }).toList());
        });
        when(orders.createTicketOrder(any())).thenAnswer(call -> new Result<String>().setCode(Result.SUCCESS_CODE)
                .setData(((TicketOrderCreateRemoteReqDTO)call.getArgument(0)).getOrderSn()));
    }

    void fixture(int... sizes) {
        long id=db.queryForObject("SELECT MAX(id)+100 FROM t_seat",Long.class);
        for(int c=0;c<sizes.length;c++) for(int n=0;n<sizes[c];n++) {
            db.update("INSERT INTO t_seat(id,train_id,carriage_number,seat_number,seat_type,start_station,end_station,price,seat_status,create_time,update_time,del_flag) "
                    +"VALUES(?,1,?,?,2,'北京南','宁波',100,0,NOW(),NOW(),0)",id,"TEST-C"+(c+1),String.format("%02dA",n+1));
            ids.add(id++);
        }
    }

    PurchaseTicketReqDTO request(int count) {
        PurchaseTicketReqDTO r=new PurchaseTicketReqDTO();r.setTrainId("1");r.setDeparture("北京南");r.setArrival("宁波");
        List<PurchaseTicketPassengerDetailDTO> list=new ArrayList<>();
        for(int n=0;n<count;n++){PurchaseTicketPassengerDetailDTO p=new PurchaseTicketPassengerDetailDTO();p.setPassengerId(""+(900+n));p.setSeatType(2);list.add(p);}
        r.setPassengers(list);return r;
    }

    int locked(){return db.queryForObject("SELECT COUNT(*) FROM t_seat WHERE carriage_number LIKE 'TEST-C%' AND seat_status=1",Integer.class);}

    @Test void singlePassengerUsesOnlyOneSeat(){fixture(3,3,3);purchase.purchaseTickets(request(1));assertEquals(1,locked());}
    @Test void twoPassengersMoveToLaterCarriage(){fixture(1,3,0);var r=purchase.purchaseTickets(request(2));assertEquals(2,locked());assertEquals(1,r.getTicketOrderDetails().stream().map(TicketOrderDetailRespDTO::getCarriageNumber).distinct().count());}
    @Test void fivePassengersPreferSingleCarriage(){fixture(2,5,2);var r=purchase.purchaseTickets(request(5));assertEquals(5,locked());assertEquals(1,r.getTicketOrderDetails().stream().map(TicketOrderDetailRespDTO::getCarriageNumber).distinct().count());}
    @Test void fragmentedInventoryFallsBackAtomically(){fixture(2,2,2);var r=purchase.purchaseTickets(request(5));assertEquals(5,locked());assertEquals(3,r.getTicketOrderDetails().stream().map(TicketOrderDetailRespDTO::getCarriageNumber).distinct().count());}
    @Test void shortageLeavesNoPartialSeats(){fixture(1,1,1);assertThrows(ServiceException.class,()->purchase.purchaseTickets(request(5)));assertEquals(0,locked());verify(tokens).returnToken(eq(1L),anyString(),anyString(),anyMap());}
    @Test void laterSeatTypeFailureRollsBackEarlierSeats(){fixture(3,3,3);var r=request(2);r.getPassengers().get(1).setSeatType(1);assertThrows(ServiceException.class,()->purchase.purchaseTickets(r));assertEquals(0,locked());verify(tokens).returnToken(eq(1L),anyString(),anyString(),anyMap());}

    @AfterEach void cleanup(){
        UserContext.removeUser();db.update("DELETE FROM t_ticket WHERE username=?",username);
        for(Long id:ids)db.update("DELETE FROM t_seat WHERE id=? AND carriage_number LIKE 'TEST-C%'",id);
    }
}
