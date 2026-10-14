package edu.swu.fcj.my12306.biz.ticketservice.toolkit;

import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.InventoryStationOrder;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainStationDO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;

/** Offline tool; never registered as a startup runner. Credentials are supplied through environment variables. */
public final class InventoryMigrationTool {
    private final JdbcTemplate db;
    public InventoryMigrationTool(JdbcTemplate db) { this.db=db; }
    public record Seat(Long id,Long trainId,Integer type,String carriage,String number) { }
    public record Ticket(Long id,String orderSn,Long trainId,Integer type,String carriage,String number,
                         String departure,String arrival,Integer status) { }
    public record Claim(Seat seat,Ticket ticket,int segment) { }
    public record Snapshot(Map<Long,List<TrainStationDO>> routes,List<Seat> seats,List<Claim> claims,List<String> conflicts) { }

    /** Read and report every conflicting ownership before writing any inventory data. */
    public Snapshot inspect() {
        var conflicts=new ArrayList<String>();
        var routes=new LinkedHashMap<Long,List<TrainStationDO>>();
        var raw=new LinkedHashMap<Long,List<TrainStationDO>>();
        db.query("SELECT train_id,sequence,departure FROM t_train_station WHERE del_flag=0",rs -> {
            TrainStationDO s=new TrainStationDO();s.setTrainId(rs.getLong(1));s.setSequence(rs.getString(2));s.setDeparture(rs.getString(3));
            raw.computeIfAbsent(s.getTrainId(),ignored -> new ArrayList<>()).add(s);
        });
        db.query("SELECT id,start_station,end_station FROM t_train WHERE del_flag=0",rs -> {
            Long id=rs.getLong(1);
            try {
                var list=InventoryStationOrder.validateAndSort(raw.getOrDefault(id,List.of()));
                if (!Objects.equals(list.getFirst().getDeparture(),rs.getString(2))
                        || !Objects.equals(list.getLast().getDeparture(),rs.getString(3))) {
                    throw new IllegalStateException("首末站不匹配");
                }
                routes.put(id,list);
            } catch (RuntimeException ex) { conflicts.add("train="+id+" invalid stations: "+ex.getMessage()); }
        });
        var physical=new LinkedHashMap<String,Seat>();
        db.query("SELECT id,train_id,seat_type,carriage_number,seat_number,start_station,end_station FROM t_seat WHERE del_flag=0 ORDER BY train_id,seat_type,carriage_number,seat_number,id",rs -> {
            Seat seat=new Seat(rs.getLong(1),rs.getObject(2,Long.class),rs.getObject(3,Integer.class),rs.getString(4),rs.getString(5));
            if (seat.trainId()==null || seat.type()==null || blank(seat.carriage()) || blank(seat.number()) || !routes.containsKey(seat.trainId())) {
                conflicts.add("seat="+seat.id()+" invalid physical coordinates");return;
            }
            String key=coordinate(seat.trainId(),seat.carriage(),seat.number());
            Seat old=physical.putIfAbsent(key,seat);
            if (old!=null && !Objects.equals(old.type(),seat.type())) conflicts.add("seat="+key+" conflicting seat types");
            if (range(routes.get(seat.trainId()),rs.getString(6),rs.getString(7))==null) conflicts.add("seat="+seat.id()+" invalid OD");
        });
        var claims=new ArrayList<Claim>();
        var owners=new HashMap<String,Long>();
        db.query("SELECT id,order_sn,train_id,seat_type,carriage_number,seat_number,start_station,end_station,ticket_status FROM t_ticket WHERE del_flag=0 AND ticket_status IN (0,10) ORDER BY id",rs -> {
            Ticket ticket=new Ticket(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getObject(4,Integer.class),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getInt(9));
            Seat seat=physical.get(coordinate(ticket.trainId(),ticket.carriage(),ticket.number()));
            int[] interval=range(routes.get(ticket.trainId()),ticket.departure(),ticket.arrival());
            if (seat==null || !Objects.equals(seat.type(),ticket.type()) || interval==null || blank(ticket.orderSn())) {
                conflicts.add("ticket="+ticket.id()+" missing seat, order or valid route");return;
            }
            for (int segment=interval[0];segment<interval[1];segment++) {
                String key=seat.id()+"/"+segment;
                Long previous=owners.putIfAbsent(key,ticket.id());
                if (previous!=null) conflicts.add("seat="+seat.id()+" segment="+segment+" overlapping tickets="+previous+","+ticket.id());
                claims.add(new Claim(seat,ticket,segment));
            }
        });
        return new Snapshot(routes,List.copyOf(physical.values()),claims,new ArrayList<>(new LinkedHashSet<>(conflicts)));
    }

    /** Caller supplies a transaction and has stopped all writers. Populating a nonempty model is forbidden. */
    public void migrate(Snapshot snapshot) {
        if (!snapshot.conflicts().isEmpty()) throw new IllegalStateException("Historical conflicts: inspect the report");
        for (String table:List.of("t_inventory_station","t_physical_seat","t_seat_segment","t_inventory_cache_event"))
            if (db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)!=0) throw new IllegalStateException("Target inventory is not empty: "+table);
        var stations=new ArrayList<Object[]>();
        snapshot.routes().forEach((train,list) -> {for(int i=0;i<list.size();i++) stations.add(new Object[]{train,i,list.get(i).getDeparture()});});
        db.batchUpdate("INSERT INTO t_inventory_station(train_id,station_index,station_name) VALUES(?,?,?)",stations);
        db.batchUpdate("INSERT INTO t_physical_seat(id,train_id,seat_type,carriage_number,seat_number) VALUES(?,?,?,?,?)",
                snapshot.seats().stream().map(s -> new Object[]{s.id(),s.trainId(),s.type(),s.carriage(),s.number()}).toList());
        var segments=new ArrayList<Object[]>();
        for (var seat:snapshot.seats()) for (int i=0;i<snapshot.routes().get(seat.trainId()).size()-1;i++)
            segments.add(new Object[]{seat.id(),i});
        // Chunk batches so large fleets do not exceed a single MySQL packet.
        for (int i=0;i<segments.size();i+=1000) db.batchUpdate("INSERT INTO t_seat_segment(seat_id,segment_index) VALUES(?,?)",segments.subList(i,Math.min(i+1000,segments.size())));
        var updates=snapshot.claims().stream().map(c -> new Object[]{c.ticket().status()==0?1:2,c.ticket().id(),c.ticket().orderSn(),c.seat().id(),c.segment()}).toList();
        for (int i=0;i<updates.size();i+=1000) db.batchUpdate("UPDATE t_seat_segment SET seat_status=?,ticket_id=?,order_sn=? WHERE seat_id=? AND segment_index=?",updates.subList(i,Math.min(i+1000,updates.size())));
        verify(snapshot);
    }

    public void verify(Snapshot snapshot) {
        if (!snapshot.conflicts().isEmpty()) throw new IllegalStateException("Source tickets conflict");
        long expected=snapshot.seats().stream().mapToLong(s -> snapshot.routes().get(s.trainId()).size()-1).sum();
        require(db.queryForObject("SELECT COUNT(*) FROM t_physical_seat",Long.class)==snapshot.seats().size(),"Physical seat count mismatch");
        var physical=db.query("SELECT id,train_id,seat_type,carriage_number,seat_number FROM t_physical_seat",(rs,n) ->
                new Seat(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getString(4),rs.getString(5)));
        require(new HashSet<>(physical).equals(new HashSet<>(snapshot.seats())),"Physical coordinates mismatch");
        require(db.queryForObject("SELECT COUNT(*) FROM t_seat_segment",Long.class)==expected,"Segment count mismatch");
        var actual=new HashMap<String,String>();
        db.query("SELECT seat_id,segment_index,seat_status,ticket_id,order_sn FROM t_seat_segment",rs -> {
            String key=rs.getLong(1)+"/"+rs.getInt(2);
            if (rs.getInt(3)==0) require(rs.getObject(4)==null && rs.getString(5)==null,"Free segment has an owner: "+key);
            else actual.put(key,rs.getInt(3)+"/"+rs.getLong(4)+"/"+rs.getString(5));
        });
        require(actual.size()==snapshot.claims().size(),"Occupied segment count mismatch");
        for (var claim:snapshot.claims()) require(Objects.equals(actual.get(claim.seat().id()+"/"+claim.segment()),
                (claim.ticket().status()==0?1:2)+"/"+claim.ticket().id()+"/"+claim.ticket().orderSn()),"Segment owner mismatch: ticket="+claim.ticket().id());
        for (var seat:snapshot.seats()) {
            long count=db.queryForObject("SELECT COUNT(*) FROM t_seat_segment WHERE seat_id=? AND segment_index>=0 AND segment_index<?",Long.class,
                    seat.id(),snapshot.routes().get(seat.trainId()).size()-1);
            require(count==snapshot.routes().get(seat.trainId()).size()-1,"Missing segment: seat="+seat.id());
        }
        long expectedStations=snapshot.routes().values().stream().mapToLong(List::size).sum();
        require(db.queryForObject("SELECT COUNT(*) FROM t_inventory_station",Long.class)==expectedStations,"Station count mismatch");
        for (var route:snapshot.routes().entrySet()) {
            var projected=db.query("SELECT station_index,station_name FROM t_inventory_station WHERE train_id=? ORDER BY station_index",(rs,n) -> {
                require(rs.getInt(1)==n,"Station index mismatch");return rs.getString(2);
            },route.getKey());
            require(projected.equals(route.getValue().stream().map(TrainStationDO::getDeparture).toList()),"Station projection mismatch: train="+route.getKey());
        }
    }

    /** Rebuild old OD availability from valid ticket overlap; never blanket-release an overlapping sold ticket. */
    public void rebuildLegacy(Snapshot snapshot) {
        if (!snapshot.conflicts().isEmpty()) throw new IllegalStateException("Cannot rebuild conflicting historical tickets");
        var claimsBySeat=new HashMap<String,List<Claim>>();
        for (var claim:snapshot.claims()) claimsBySeat.computeIfAbsent(coordinate(claim.seat().trainId(),claim.seat().carriage(),claim.seat().number()),ignored -> new ArrayList<>()).add(claim);
        var updates=new ArrayList<Object[]>();
        db.query("SELECT id,train_id,carriage_number,seat_number,start_station,end_station FROM t_seat WHERE del_flag=0",rs -> {
            var interval=range(snapshot.routes().get(rs.getLong(2)),rs.getString(5),rs.getString(6));
            require(interval!=null,"Invalid legacy OD");int status=0;
            for (var claim:claimsBySeat.getOrDefault(coordinate(rs.getLong(2),rs.getString(3),rs.getString(4)),List.of()))
                if (claim.segment()>=interval[0] && claim.segment()<interval[1]) status=Math.max(status,claim.ticket().status()==10?2:1);
            updates.add(new Object[]{status,rs.getLong(1)});
        });
        for(int i=0;i<updates.size();i+=1000) db.batchUpdate("UPDATE t_seat SET seat_status=?,update_time=CURRENT_TIMESTAMP WHERE id=?",updates.subList(i,Math.min(i+1000,updates.size())));
    }

    private static void require(boolean value,String message) {if(!value) throw new IllegalStateException(message);}
    private static boolean blank(String value) {return value==null || value.isBlank();}
    private static String coordinate(Long train,String carriage,String number) {return train+"/"+carriage+"/"+number;}
    private static int[] range(List<TrainStationDO> route,String start,String end) {
        if(route==null) return null;int a=-1,b=-1;
        for(int i=0;i<route.size();i++) {if(Objects.equals(route.get(i).getDeparture(),start)) a=i;if(Objects.equals(route.get(i).getDeparture(),end)) b=i;}
        return a>=0 && b>a?new int[]{a,b}:null;
    }

    public static void main(String[] args) throws Exception {
        var options=List.of(args);
        if(args.length==0 || !List.of("check","migrate","verify","rebuild-legacy").contains(args[0]))
            throw new IllegalArgumentException("check|migrate|verify|rebuild-legacy [--writers-stopped] [--report=path]");
        if(!args[0].equals("check") && !options.contains("--writers-stopped"))
            throw new IllegalArgumentException("Stop all writers and pass --writers-stopped before migration/verification/rollback");
        String url=System.getenv("TICKET_DB_URL"),user=System.getenv("TICKET_DB_USER"),password=System.getenv("TICKET_DB_PASSWORD");
        if(url==null || user==null || password==null) throw new IllegalArgumentException("Set TICKET_DB_URL, TICKET_DB_USER, TICKET_DB_PASSWORD");
        var source=new DriverManagerDataSource(url,user,password);
        var tool=new InventoryMigrationTool(new JdbcTemplate(source));
        // DDL is outside the data transaction because MySQL DDL implicitly commits.
        if(args[0].equals("migrate")) new ResourceDatabasePopulator(new ClassPathResource("db/12306_ticket_segment_schema.sql")).execute(source);
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        var snapshot=transaction.execute(status -> {
            var inspected=tool.inspect();
            if(inspected.conflicts().isEmpty()) switch(args[0]) {
                case "migrate" -> tool.migrate(inspected);
                case "verify" -> tool.verify(inspected);
                case "rebuild-legacy" -> tool.rebuildLegacy(inspected);
            }
            return inspected;
        });
        var report=new LinkedHashMap<String,Object>();report.put("mode",args[0]);report.put("trains",snapshot.routes().size());
        report.put("physicalSeats",snapshot.seats().size());report.put("occupiedSegments",snapshot.claims().size());report.put("conflicts",snapshot.conflicts());
        String json=new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report);
        String path=options.stream().filter(x -> x.startsWith("--report=")).map(x -> x.substring(9)).findFirst().orElse(null);
        if(path!=null) Files.writeString(Path.of(path),json);
        System.out.println(json);
        if(!snapshot.conflicts().isEmpty()) throw new IllegalStateException("Migration blocked by conflicts; no inventory data was changed");
    }
}
