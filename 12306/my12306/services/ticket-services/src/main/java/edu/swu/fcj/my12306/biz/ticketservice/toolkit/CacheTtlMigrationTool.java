package edu.swu.fcj.my12306.biz.ticketservice.toolkit;

import edu.swu.fcj.my12306.biz.ticketservice.TicketServicesApplication;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.CacheTtlPolicy;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit rollout repair for legacy persistent caches; dry-run unless --apply is supplied. */
public final class CacheTtlMigrationTool {
    public static void main(String[] args) {
        boolean apply=args.length==1&&args[0].equals("--apply");
        if(args.length>1||(args.length==1&&!apply))throw new IllegalArgumentException("Usage: [--apply]; default is read-only audit");
        try(var context=new SpringApplicationBuilder(TicketServicesApplication.class).web(WebApplicationType.NONE).run(
                "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.discovery.enabled=false",
                "--my12306.ticket.orphan-scan-enabled=false","--my12306.inventory.cache-events.enabled=false","--my12306.pay.notify-mode=feign")){
            var redis=context.getBean(StringRedisTemplate.class);var policy=context.getBean(CacheTtlPolicy.class);
            Result result=repair(redis,policy,apply,context.getEnvironment().getProperty("my12306.inventory.remaining-ttl-seconds",Long.class,30L));
            System.out.println("Legacy permanent cache keys="+result.permanentKeys()+", TTLs repaired="+result.repairedKeys()+", mode="+(apply?"apply":"read-only"));
        }
    }
    public record Result(int permanentKeys,int repairedKeys){}
    public static Result repair(StringRedisTemplate redis,CacheTtlPolicy policy,boolean apply,long remainingUpper){
            var prefixes=List.of("region_train_station_mapping","region_train_station","query_all_region_list",
                    "train_info","train_station_price","region-station","all_station","train_station_remaining_ticket");
            var script=new DefaultRedisScript<Long>("if redis.call('TTL',KEYS[1])==-1 then return redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return 0",Long.class);
            String base="my12306-ticket-service:";
            // Use the key serializer so the same tool can audit an isolated test namespace.
            @SuppressWarnings("unchecked") var serializer=(org.springframework.data.redis.serializer.RedisSerializer<String>)redis.getKeySerializer();
            String pattern=new String(serializer.serialize(base+"*"),StandardCharsets.UTF_8);
            int permanent=0,changed=0;
            try(var connection=redis.getConnectionFactory().getConnection();var cursor=connection.keyCommands().scan(ScanOptions.scanOptions().match(pattern).count(200).build())){
                while(cursor.hasNext()){
                    String key=serializer.deserialize(cursor.next());String family=prefixes.stream().filter(p->key.startsWith(base+p)).findFirst().orElse(null);
                    if(family==null||!Long.valueOf(-1).equals(redis.getExpire(key)))continue;
                    long seconds=switch(family){
                        case "region_train_station_mapping","query_all_region_list" -> policy.snapshot();
                        case "region_train_station" -> policy.cityPair();
                        case "train_station_remaining_ticket" -> policy.remaining(remainingUpper);
                        default -> policy.positive(15*86400L);
                    };
                    permanent++;
                    if(apply&&Long.valueOf(1).equals(redis.execute(script,List.of(key),Long.toString(seconds))))changed++;
                }
            }
            return new Result(permanent,changed);
    }
}
