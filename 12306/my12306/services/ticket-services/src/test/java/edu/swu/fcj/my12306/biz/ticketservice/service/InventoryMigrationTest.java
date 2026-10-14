package edu.swu.fcj.my12306.biz.ticketservice.service;

import edu.swu.fcj.my12306.biz.ticketservice.toolkit.InventoryMigrationTool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** A task-owned empty database exercises both DDL and migration rollback without touching live data. */
@EnabledIfEnvironmentVariable(named="TICKET_DB_URL",matches=".*codex_segment_test_.*")
class InventoryMigrationTest {
    String name;
    JdbcTemplate admin,db;
    TransactionTemplate transaction;
    InventoryMigrationTool tool;
    @BeforeEach void setup() {
        String url=System.getenv("TICKET_DB_URL"),user=System.getenv("TICKET_DB_USER"),password=System.getenv("TICKET_DB_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        name="codex_segment_migration_"+Long.toUnsignedString(System.nanoTime());
        String source=admin.queryForObject("SELECT DATABASE()",String.class);
        assertTrue(source.matches("codex_segment_test_[0-9]+"));
        admin.execute("CREATE DATABASE "+name+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var dataSource=new DriverManagerDataSource(url.replace("/"+source+"?","/"+name+"?"),user,password);
        db=new JdbcTemplate(dataSource);transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));tool=new InventoryMigrationTool(db);
        for(String table:List.of("t_train","t_train_station","t_seat","t_ticket"))
            db.execute("CREATE TABLE "+table+" LIKE "+source+"."+table);
        new ResourceDatabasePopulator(new ClassPathResource("db/12306_ticket_segment_schema.sql")).execute(dataSource);
        db.update("INSERT INTO t_train(id,start_station,end_station,del_flag) VALUES(990,'A','D',0)");
        for(int i=0;i<4;i++) db.update("INSERT INTO t_train_station(id,train_id,sequence,departure,del_flag) VALUES(?,990,?,?,0)",990+i,""+(i+1),"ABCD".substring(i,i+1));
        for(int i=0;i<3;i++) for(int j=i+1;j<4;j++)
            db.update("INSERT INTO t_seat(id,train_id,seat_type,carriage_number,seat_number,start_station,end_station,seat_status,del_flag) VALUES(?,990,2,'01','01A',?,?,0,0)",99000+i*10+j,"ABCD".substring(i,i+1),"ABCD".substring(j,j+1));
    }
    @AfterEach void cleanup() {
        if(name!=null && name.matches("codex_segment_migration_[0-9]+")) admin.execute("DROP DATABASE "+name);
    }
    void ticket(long id,String start,String end,int status) {
        db.update("INSERT INTO t_ticket(id,order_sn,train_id,seat_type,carriage_number,seat_number,start_station,end_station,ticket_status,del_flag) VALUES(?, ?,990,2,'01','01A',?,?,?,0)",id,"migration-"+id,start,end,status);
    }
    @Test void adjacentUnpaidAndPaidTicketsMigrateWithExactOwnershipAndVerify() {
        ticket(1,"A","B",0);ticket(2,"B","C",10);
        var snapshot=tool.inspect();assertTrue(snapshot.conflicts().isEmpty());
        transaction.executeWithoutResult(s -> tool.migrate(snapshot));tool.verify(tool.inspect());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM t_physical_seat",Integer.class));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM t_seat_segment",Integer.class));
        assertEquals(List.of(1,2,0),db.queryForList("SELECT seat_status FROM t_seat_segment ORDER BY segment_index",Integer.class));
        assertEquals(List.of(1L,2L),db.queryForList("SELECT ticket_id FROM t_seat_segment WHERE ticket_id IS NOT NULL ORDER BY segment_index",Long.class));
        assertThrows(IllegalStateException.class,() -> transaction.executeWithoutResult(s -> tool.migrate(snapshot)));
    }
    @Test void overlappingHistoryBlocksMigrationWithoutWritingInventory() {
        ticket(1,"A","C",0);ticket(2,"B","D",10);
        var snapshot=tool.inspect();assertTrue(snapshot.conflicts().stream().anyMatch(x -> x.contains("overlapping tickets=1,2")));
        assertThrows(IllegalStateException.class,() -> transaction.executeWithoutResult(s -> tool.migrate(snapshot)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM t_physical_seat",Integer.class));
    }
    @Test void missingSeatAndInvalidStationSequenceAreReported() {
        ticket(1,"A","C",0);db.update("UPDATE t_ticket SET seat_number='missing' WHERE id=1");
        assertTrue(tool.inspect().conflicts().stream().anyMatch(x -> x.contains("missing seat")));
        db.update("UPDATE t_train_station SET sequence='10' WHERE sequence='2'");
        assertTrue(tool.inspect().conflicts().stream().anyMatch(x -> x.contains("invalid stations")));
    }
    @Test void failedMigrationRollsBackAllPopulatedDataAndVerificationFindsMissingSegments() {
        var snapshot=tool.inspect();
        assertThrows(IllegalStateException.class,() -> transaction.execute(s -> {tool.migrate(snapshot);throw new IllegalStateException("rollback");}));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM t_seat_segment",Integer.class));
        transaction.executeWithoutResult(s -> tool.migrate(snapshot));
        db.update("DELETE FROM t_seat_segment WHERE segment_index=1");
        assertThrows(IllegalStateException.class,() -> tool.verify(tool.inspect()));
    }
    @Test void rebuildingLegacyUsesRemainingTicketOverlapRatherThanBlindlyUnlocking() {
        ticket(1,"A","B",30);ticket(2,"B","C",10);
        transaction.executeWithoutResult(s -> tool.rebuildLegacy(tool.inspect()));
        assertEquals(0,db.queryForObject("SELECT seat_status FROM t_seat WHERE start_station='A' AND end_station='B'",Integer.class));
        assertEquals(2,db.queryForObject("SELECT seat_status FROM t_seat WHERE start_station='A' AND end_station='C'",Integer.class));
    }
}
