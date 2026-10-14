package edu.swu.fcj.my12306.biz.ticketservice.common.cache;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.springframework.stereotype.Component;
import java.sql.Connection;

/** Count actual prepared SQL, rather than counting mapper invocations served by the session cache. */
@Component
@Intercepts(@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}))
public class CacheSqlDiagnostics implements Interceptor {
    private final CacheDiagnostics diagnostics;
    public CacheSqlDiagnostics(CacheDiagnostics diagnostics){this.diagnostics=diagnostics;}
    @Override public Object intercept(Invocation invocation)throws Throwable {
        String sql=((StatementHandler)invocation.getTarget()).getBoundSql().getSql().stripLeading().toLowerCase(java.util.Locale.ROOT);
        String kind=sql.contains("t_inventory_cache_event")?"event":sql.contains("t_seat_segment")?
                (sql.startsWith("select")?"inventory-read":"inventory-write"):sql.startsWith("select")?"metadata-read":"other-write";
        diagnostics.sql(kind);return invocation.proceed();
    }
}
