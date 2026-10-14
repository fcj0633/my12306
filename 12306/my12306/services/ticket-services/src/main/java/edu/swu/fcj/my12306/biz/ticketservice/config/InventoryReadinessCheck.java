package edu.swu.fcj.my12306.biz.ticketservice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fail startup instead of exposing an application still backed by missing/partial segment inventory. */
@Component
@RequiredArgsConstructor
public class InventoryReadinessCheck implements ApplicationRunner {
    private final JdbcTemplate db;
    @Override public void run(ApplicationArguments arguments) {
        try {
            Long seats=db.queryForObject("SELECT COUNT(*) FROM t_physical_seat",Long.class);
            Long bad=db.queryForObject("""
                    SELECT COUNT(*) FROM (
                      SELECT p.id,COUNT(s.segment_index) AS actual,
                        (SELECT COUNT(*)-1 FROM t_inventory_station i WHERE i.train_id=p.train_id) AS expected
                      FROM t_physical_seat p LEFT JOIN t_seat_segment s ON s.seat_id=p.id
                      GROUP BY p.id,p.train_id
                      HAVING expected<1 OR actual<>expected OR MIN(s.segment_index)<>0 OR MAX(s.segment_index)<>expected-1
                    ) invalid_inventory
                    """,Long.class);
            if(seats==null || seats==0 || bad==null || bad!=0) throw new IllegalStateException("区段库存为空或不完整");
        } catch(RuntimeException ex) {
            throw new IllegalStateException("区段库存尚未正确迁移，请停写后执行 InventoryMigrationTool 并完成 verify，再启动票务服务",ex);
        }
    }
}
