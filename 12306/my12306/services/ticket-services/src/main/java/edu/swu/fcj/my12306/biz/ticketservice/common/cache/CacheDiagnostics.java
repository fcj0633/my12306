package edu.swu.fcj.my12306.biz.ticketservice.common.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Finite cache-family labels; request ids and actual business keys never become metric labels. */
@Component
public class CacheDiagnostics {
    private final MeterRegistry metrics;
    private final ThreadLocal<Deque<Load>> loads=ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Integer> lockDepth=ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Set<String>> seen=new ThreadLocal<>();
    public CacheDiagnostics(MeterRegistry metrics) {this.metrics=metrics;}
    static CacheDiagnostics noop() {return new CacheDiagnostics(null);}
    public static String family(String key) {
        for(String name:List.of("train_station_remaining_ticket","region_train_station_mapping","region_train_station",
                "query_all_region_list","train_station_price","train_info","region-station","all_station","train_station_token_bucket"))
            if(key.contains(":"+name)) return name.replace('-','_');
        return "other";
    }
    public AutoCloseable request() {
        Set<String> previous=seen.get();seen.set(new HashSet<>());
        return () -> {if(previous==null) seen.remove();else seen.set(previous);};
    }
    public void lookup(String key,String identity,String result) {
        if(metrics==null)return;
        String phase=lockDepth.get()>0?"locked":"first";
        Set<String> keys=seen.get();
        if(keys!=null && !keys.add(identity) && !phase.equals("locked")) phase="repeat";
        metrics.counter("my12306.cache.lookup","family",family(key),"phase",phase,"result",result).increment();
    }
    public <T> T loading(String key,Supplier<T> action) {
        long before=System.nanoTime();Load load=new Load(family(key));loads.get().push(load);
        String outcome="success";
        try{return action.get();}catch(RuntimeException ex){outcome="failure";throw ex;}
        finally {
            loads.get().pop();if(loads.get().isEmpty())loads.remove();
            if(metrics!=null){metrics.timer("my12306.cache.load","family",load.family,"outcome",outcome).record(System.nanoTime()-before,TimeUnit.NANOSECONDS);
                metrics.summary("my12306.cache.load.sql","family",load.family,"outcome",outcome).record(load.sql);}
        }
    }
    public void sql(String kind) {
        for(Load load:loads.get()) load.sql++;
        if(metrics!=null)metrics.counter("my12306.cache.sql","kind",kind).increment();
    }
    public void lockAcquired(String key,long nanos) {
        lockDepth.set(lockDepth.get()+1);
        if(metrics!=null)metrics.timer("my12306.cache.lock.wait","family",family(key)).record(nanos,TimeUnit.NANOSECONDS);
    }
    public void lockReleased() {int depth=lockDepth.get()-1;if(depth==0)lockDepth.remove();else lockDepth.set(depth);}
    public void count(String name,String family,long amount) {if(metrics!=null)metrics.counter("my12306.cache."+name,"family",family).increment(amount);}
    private static class Load {final String family;long sql;Load(String family){this.family=family;}}
}
