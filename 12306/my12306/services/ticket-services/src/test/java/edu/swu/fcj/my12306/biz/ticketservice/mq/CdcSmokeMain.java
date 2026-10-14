package edu.swu.fcj.my12306.biz.ticketservice.mq;

import edu.swu.fcj.my12306.biz.ticketservice.TicketServicesApplication;
import edu.swu.fcj.my12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.InventoryRouteService;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.CacheUtil;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.NameMapper;
import java.util.*;
import java.sql.Connection;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/** A bounded real-chain experiment; refuses business databases and unprefixed Redis. */
public class CdcSmokeMain {
    @TestConfiguration public static class Config {
        @Bean public LettuceConnectionFactory redisConnectionFactory() {
            var cfg=new RedisStandaloneConfiguration(System.getenv("SEGMENT_REDIS_HOST"),Integer.parseInt(System.getenv("SEGMENT_REDIS_PORT")));
            var password=System.getenv("SEGMENT_REDIS_PASSWORD"); if(password!=null&&!password.isBlank()) cfg.setPassword(password);
            return new LettuceConnectionFactory(cfg);
        }
        @Bean public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
            var template=new StringRedisTemplate(factory); String prefix=System.getenv("CACHE_TEST_PREFIX");
            template.setKeySerializer(new StringRedisSerializer() {
                @Override public byte[] serialize(String value) { return super.serialize(prefix+value); }
                @Override public String deserialize(byte[] value) { String text=super.deserialize(value); return text==null?null:text.substring(prefix.length()); }
            }); return template;
        }
        @Bean(destroyMethod="shutdown") public RedissonClient redissonClient() {
            var cfg=new org.redisson.config.Config(); cfg.setThreads(4);cfg.setNettyThreads(4);
            var server=cfg.useSingleServer().setAddress("redis://"+System.getenv("SEGMENT_REDIS_HOST")+":"+System.getenv("SEGMENT_REDIS_PORT"));
            var password=System.getenv("SEGMENT_REDIS_PASSWORD"); if(password!=null&&!password.isBlank())server.setPassword(password);
            String prefix=System.getenv("CACHE_TEST_PREFIX");
            server.setNameMapper(new NameMapper(){public String map(String name){return prefix+name;}public String unmap(String name){return name.substring(prefix.length());}});
            return Redisson.create(cfg);
        }
    }
    public static void main(String[] args) throws Exception {
        String database=System.getenv("MY12306_CDC_DATABASE"),prefix=System.getenv("CACHE_TEST_PREFIX");
        if(database==null || !database.matches("codex_cdc_test_[0-9]+") || prefix==null || !prefix.matches("codex:cdc:[0-9]+:"))
            throw new IllegalArgumentException("Smoke test requires owned database and Redis namespace");
        try(var context=new SpringApplicationBuilder(TicketServicesApplication.class,Config.class).properties(
                "server.port=0","spring.main.allow-bean-definition-overriding=true","spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.discovery.enabled=false","my12306.pay.notify-mode=feign","my12306.ticket.orphan-scan-enabled=false").run()) {
            var db=context.getBean(JdbcTemplate.class); var redis=context.getBean(StringRedisTemplate.class);
            var loader=context.getBean(SeatMarginCacheLoader.class); var routes=context.getBean(InventoryRouteService.class);
            var cache=context.getBean(RedisCacheHelper.class);var metrics=context.getBean(MeterRegistry.class);
            var consumer=context.getBean("inventoryCdcPushConsumer",org.apache.rocketmq.client.consumer.DefaultMQPushConsumer.class);
            long readyUntil=System.nanoTime()+30_000_000_000L;
            while(consumer.getDefaultMQPushConsumerImpl().getRebalanceImpl().getProcessQueueTable().isEmpty()
                    && System.nanoTime()<readyUntil)Thread.sleep(100);
            if(consumer.getDefaultMQPushConsumerImpl().getRebalanceImpl().getProcessQueueTable().isEmpty())
                throw new IllegalStateException("CDC consumer has no assigned queues; test commits have not started");
            var stations=routes.stations(1L); var ids=db.queryForList("SELECT id FROM t_physical_seat WHERE train_id=1 AND seat_type=2 AND del_flag=0 ORDER BY id LIMIT 1",Long.class);
            long seat=ids.getFirst(); var affected=new ArrayList<String>(); var unaffected=new ArrayList<String>();
            for(int i=0;i<stations.size()-1;i++)for(int j=i+1;j<stations.size();j++) {
                loader.load("1",2,stations.get(i).getStationName(),stations.get(j).getStationName());
                String key=TRAIN_STATION_REMAINING_TICKET+CacheUtil.buildKey("1",stations.get(i).getStationName(),stations.get(j).getStationName());
                (i<=1&&1<j?affected:unaffected).add(key);
            }
            String observed=affected.getFirst(); long baseline=Long.parseLong(redis.opsForHash().get(observed,"2").toString());
            var result=new LinkedHashMap<String,Object>(); result.put("database",database);result.put("overlappingKeys",affected.size());result.put("preservedKeys",unaffected.size());
            try {
                for(boolean release:List.of(false,true)) {
                    // Refresh before each mutation, so success cannot be explained by the 27–30s natural expiry.
                    for(int i=0;i<stations.size()-1;i++)for(int j=i+1;j<stations.size();j++) loader.load("1",2,stations.get(i).getStationName(),stations.get(j).getStationName());
                    double beforeSuccess=metrics.counter("my12306.cdc.consumer.success").count();
                    long commitAt,commitEpochMillis;
                    try(Connection connection=context.getBean(DataSource.class).getConnection()) {
                        connection.setAutoCommit(false);
                        try(var statement=connection.prepareStatement(release?
                                "UPDATE t_seat_segment SET seat_status=0,ticket_id=NULL,order_sn=NULL WHERE seat_id=? AND segment_index=1 AND seat_status=1":
                                "UPDATE t_seat_segment SET seat_status=1,ticket_id=900000001,order_sn='cdc-smoke' WHERE seat_id=? AND segment_index=1 AND seat_status=0")) {
                            statement.setLong(1,seat); if(statement.executeUpdate()!=1)throw new IllegalStateException("Unexpected fixture state");
                        }
                        connection.commit(); commitAt=System.nanoTime();commitEpochMillis=System.currentTimeMillis();
                    }
                    long deadline=commitAt+10_000_000_000L;
                    while(System.nanoTime()<deadline && (affected.stream().anyMatch(k->Boolean.TRUE.equals(redis.hasKey(k)))
                            || metrics.counter("my12306.cdc.consumer.success").count()<=beforeSuccess)) Thread.sleep(20);
                    double invalidatedMs=(System.nanoTime()-commitAt)/1e6;
                    if(affected.stream().anyMatch(k->Boolean.TRUE.equals(redis.hasKey(k))))throw new IllegalStateException("CDC invalidation exceeded 10 seconds");
                    if(unaffected.stream().anyMatch(k->!Boolean.TRUE.equals(redis.hasKey(k))))throw new IllegalStateException("Adjacent/unrelated cache incorrectly deleted");
                    var correct=new LinkedHashMap<String,Long>();
                    for(int i=0;i<stations.size()-1;i++)for(int j=i+1;j<stations.size();j++) {
                        var loaded=loader.load("1",2,stations.get(i).getStationName(),stations.get(j).getStationName());
                        Long count=db.queryForObject("SELECT COUNT(*) FROM t_physical_seat p WHERE train_id=1 AND seat_type=2 AND del_flag=0 AND (SELECT COUNT(*) FROM t_seat_segment s WHERE s.seat_id=p.id AND s.segment_index>=? AND s.segment_index<? AND seat_status=0 AND ticket_id IS NULL AND order_sn IS NULL)=?",Long.class,i,j,j-i);
                        if(Long.parseLong(loaded.get("2"))!=count)throw new IllegalStateException("Cache disagrees with database");
                        correct.put(i+"-"+j,count);
                    }
                    result.put(release?"release":"reserve",Map.of("commitReturnedAt",commitEpochMillis,"commitToInvalidatedMs",invalidatedMs,"commitToReloadCorrectMs",(System.nanoTime()-commitAt)/1e6,"counts",correct));
                }
                if(db.queryForObject("SELECT COUNT(*) FROM t_inventory_cache_event",Long.class)!=0)throw new IllegalStateException("Unexpected business outbox event");
                result.put("businessOutboxEvents",0); result.put("baselineSeatType2",baseline);result.put("passed",true);
                System.out.println("CDC_SMOKE_RESULT "+new ObjectMapper().writeValueAsString(result));
            } finally {
                db.update("UPDATE t_seat_segment SET seat_status=0,ticket_id=NULL,order_sn=NULL WHERE seat_id=? AND segment_index=1",seat);
                // Only this owned namespace; serializer prefixes keys for both lookup and deletion.
                var keys=redis.keys("*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
            }
        }
    }
}
