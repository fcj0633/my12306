package edu.swu.fcj.my12306.biz.ticketservice.service.cachebench;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.swu.fcj.my12306.biz.ticketservice.TicketServicesApplication;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.*;
import edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant;
import edu.swu.fcj.my12306.biz.ticketservice.common.toolkit.CacheUtil;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.*;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.*;
import edu.swu.fcj.my12306.biz.ticketservice.remote.dto.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.service.*;
import edu.swu.fcj.my12306.biz.ticketservice.service.impl.*;
import edu.swu.fcj.my12306.biz.ticketservice.service.cache.*;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.filter.purchase.TrainPurchaseTicketParamVerifyChainFilter;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat.CarriageDirectory;
import io.micrometer.core.instrument.*;
import org.redisson.*;
import org.redisson.api.*;
import org.redisson.config.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.*;

/** Test-only interactive driver; all mutations require an isolated database and prefixed Redis clients. */
public class CacheBenchmarkMain {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String USER="cache-ab-buyer";
    static final AtomicLong ORDER=new AtomicLong();
    static final ThreadLocal<String> ORDER_CONTEXT=new ThreadLocal<>();
    static final ConcurrentHashMap<String,Long> COMMITS=new ConcurrentHashMap<>();
    static final Queue<Invalidation> INVALIDATIONS=new ConcurrentLinkedQueue<>();
    static final ConcurrentHashMap<Long,String> EVENT_ORDERS=new ConcurrentHashMap<>();
    static final ThreadLocal<Invalidation> LAST_INVALIDATION=new ThreadLocal<>();
    static volatile boolean trace;
    static final AtomicBoolean BLOCK=new AtomicBoolean();
    static volatile CountDownLatch captured,proceed;
    static volatile long delayMillis;
    static volatile boolean failInvalidation;
    static volatile long relationPauseMillis;
    static final AtomicInteger relationActive=new AtomicInteger(),relationPeak=new AtomicInteger();
    record Invalidation(long time,List<String> keys,String order){}

    @TestConfiguration
    public static class Config {
        @Bean public LettuceConnectionFactory redisConnectionFactory(){
            var cfg=new RedisStandaloneConfiguration(System.getenv("SEGMENT_REDIS_HOST"),Integer.parseInt(System.getenv("SEGMENT_REDIS_PORT")));
            String password=System.getenv("SEGMENT_REDIS_PASSWORD");if(password!=null&&!password.isBlank())cfg.setPassword(password);
            var factory=new LettuceConnectionFactory(cfg);return factory;
        }
        @Bean public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory){
            var template=new StringRedisTemplate(factory);String prefix=System.getenv("CACHE_TEST_PREFIX");
            template.setKeySerializer(new StringRedisSerializer(){
                @Override public byte[] serialize(String value){return super.serialize(prefix+value);}
                @Override public String deserialize(byte[] value){String text=super.deserialize(value);return text==null?null:text.substring(prefix.length());}
            });return template;
        }
        @Bean(destroyMethod="shutdown") public RedissonClient redissonClient(){
            String prefix=System.getenv("CACHE_TEST_PREFIX");var cfg=new org.redisson.config.Config();cfg.setThreads(4);cfg.setNettyThreads(4);
            var server=cfg.useSingleServer().setAddress("redis://"+System.getenv("SEGMENT_REDIS_HOST")+":"+System.getenv("SEGMENT_REDIS_PORT"));
            String password=System.getenv("SEGMENT_REDIS_PASSWORD");if(password!=null&&!password.isBlank())server.setPassword(password);
            server.setNameMapper(new NameMapper(){public String map(String name){return prefix+name;}public String unmap(String name){return name.substring(prefix.length());}});
            return Redisson.create(cfg);
        }
        @Bean public static BeanPostProcessor benchmarkHooks(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){
                if(bean instanceof DataSource source)return new AbstractDataSource(){
                    public Connection getConnection()throws SQLException{return connection(source.getConnection());}
                    public Connection getConnection(String user,String password)throws SQLException{return connection(source.getConnection(user,password));}
                };
                if(bean instanceof SeatMapper || bean instanceof RedisCacheHelper || bean instanceof edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.InventoryCacheEventMapper || bean instanceof edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainStationRelationMapper){
                    var factory=new ProxyFactory(bean);factory.setProxyTargetClass(bean instanceof RedisCacheHelper);
                    factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) call -> {
                        String method=call.getMethod().getName();
                        boolean invalidating=method.equals("deleteAll")||method.equals("invalidateRemaining");
                        if(invalidating&&failInvalidation)throw new org.springframework.data.redis.RedisConnectionFailureException("test-only invalidation connection fault");
                        boolean pauseRelation=bean instanceof edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainStationRelationMapper&&method.equals("selectList")&&relationPauseMillis>0;
                        Object value;
                        if(pauseRelation){int active=relationActive.incrementAndGet();relationPeak.accumulateAndGet(active,Math::max);
                            try{value=call.proceed();Thread.sleep(relationPauseMillis);}finally{relationActive.decrementAndGet();}
                        }else value=call.proceed();
                        if(method.equals("countAvailableByType")&&BLOCK.compareAndSet(true,false)){
                            captured.countDown();if(!proceed.await(15,TimeUnit.SECONDS))throw new IllegalStateException("snapshot barrier timeout");
                        }
                        if(bean instanceof RedisCacheHelper&&method.equals("diagnosed")&&delayMillis>0)Thread.sleep(delayMillis);
                        if(invalidating&&trace){
                            @SuppressWarnings("unchecked") var keys=(List<String>)call.getArguments()[0];
                            LAST_INVALIDATION.set(new Invalidation(System.nanoTime(),List.copyOf(keys),null));
                        }
                        if(bean instanceof edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.InventoryCacheEventMapper && trace){
                            if(method.equals("insert") && ORDER_CONTEXT.get()!=null){
                                var event=(edu.swu.fcj.my12306.biz.ticketservice.dao.entity.InventoryCacheEventDO)call.getArguments()[0];
                                EVENT_ORDERS.put(event.getId(),ORDER_CONTEXT.get());
                            }
                            if(method.equals("deleteById") && call.getArguments()[0] instanceof Number id){
                                var last=LAST_INVALIDATION.get();String order=EVENT_ORDERS.get(id.longValue());
                                if(last!=null&&order!=null)INVALIDATIONS.add(new Invalidation(last.time,last.keys,order));
                                LAST_INVALIDATION.remove();
                            }
                        }
                        return value;
                    });return factory.getProxy();
                }
                return bean;
            }
        };}
        static Connection connection(Connection connection){return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            try{
                Object result=method.invoke(connection,args);
                if(method.getName().equals("commit")&&trace&&ORDER_CONTEXT.get()!=null)COMMITS.put(ORDER_CONTEXT.get(),System.nanoTime());
                return result;
            }catch(InvocationTargetException ex){throw ex.getCause();}
        });}
    }
    final ServletWebServerApplicationContext context;
    final JdbcTemplate db;
    final TicketService tickets;
    final RegionStationService regions;
    final TrainPurchaseTicketParamVerifyChainFilter purchaseFilter;
    final PurchaseTicketTxService purchase;
    final TicketCallbackService callbacks;
    final SeatMarginCacheLoader remaining;
    final RedisCacheHelper cache;
    final StringRedisTemplate redis;
    final CacheDiagnostics diagnostics;
    final MeterRegistry metrics;
    final RedissonClient locks;
    final CarriageDirectory carriages;
    final InventoryRouteService routes;
    final List<TicketPageQueryReqDTO> emptyPairs;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final String baseUrl;
    final String model;
    CacheBenchmarkMain(ServletWebServerApplicationContext context,String model)throws Exception{
        this.context=context;this.model=model;db=context.getBean(JdbcTemplate.class);tickets=context.getBean(TicketService.class);
        regions=context.getBean(RegionStationService.class);purchaseFilter=context.getBean(TrainPurchaseTicketParamVerifyChainFilter.class);
        purchase=context.getBean(PurchaseTicketTxService.class);callbacks=context.getBean(TicketCallbackService.class);
        remaining=context.getBean(SeatMarginCacheLoader.class);cache=context.getBean(RedisCacheHelper.class);redis=context.getBean(StringRedisTemplate.class);
        diagnostics=context.getBean(CacheDiagnostics.class);metrics=context.getBean(MeterRegistry.class);locks=context.getBean(RedissonClient.class);
        carriages=context.getBean(CarriageDirectory.class);routes=context.getBean(InventoryRouteService.class);
        baseUrl="http://127.0.0.1:"+context.getWebServer().getPort();
        try(var c=db.getDataSource().getConnection()){if(!c.getCatalog().matches("codex_cache_test_[0-9]+_[AB]"))throw new IllegalStateException("Refusing non-isolated database");}
        if(!System.getenv("CACHE_TEST_PREFIX").matches("codex:cache-ab:[a-zA-Z0-9_-]+:"))throw new IllegalStateException("Invalid test prefix");
        emptyPairs=new ArrayList<>();var locations=db.queryForList("SELECT code,name FROM t_region WHERE code IS NOT NULL ORDER BY id LIMIT 30");
        for(var a:locations)for(var b:locations){
            if(a==b)continue;
            if(db.queryForObject("SELECT COUNT(*) FROM t_train_station_relation WHERE start_region=? AND end_region=?",Integer.class,a.get("name"),b.get("name"))==0)
                emptyPairs.add(query(a.get("code").toString(),b.get("code").toString()));
            if(emptyPairs.size()>=50)break;
        }
        if(emptyPairs.size()<20)throw new IllegalStateException("Need at least 20 valid empty city pairs");
        routes.stations(1L);carriages.carriages(1L,2);client.send(HttpRequest.newBuilder(URI.create(baseUrl+"/actuator/health")).GET().build(),HttpResponse.BodyHandlers.discarding());
    }
    static TicketPageQueryReqDTO query(String from,String to){var request=new TicketPageQueryReqDTO();request.setFromStation(from);request.setToStation(to);request.setDepartureDate(java.util.Date.from(LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()));return request;}
    static TicketPageQueryReqDTO hot(){return query("VNP","NGH");}
    String key(String from,String to){return TRAIN_STATION_REMAINING_TICKET+CacheUtil.buildKey("1",from,to);}
    PurchaseReservationResult buy(String sn,String from,String to)throws Exception{
        var passenger=new PurchaseTicketPassengerDetailDTO();passenger.setPassengerId("900");passenger.setSeatType(2);
        var actual=new PassengerActualRespDTO();actual.setId("900");
        var request=new PurchaseTicketReqDTO();request.setTrainId("1");request.setDeparture(from);request.setArrival(to);request.setPassengers(List.of(passenger));
        var directory=carriages.carriages(1L,2);String carriage=directory.get(carriages.nextStart(1L,2,directory.size()));
        var lock=locks.getLock(String.format(LOCK_PURCHASE_TICKETS_CARRIAGE,"1",2,carriage));lock.lock();
        ORDER_CONTEXT.set(sn);
        try{return purchase.doPurchaseInTransaction(request,"1001",USER,sn,Map.of("900",actual),Map.of(2,carriage),new PurchaseMetadata("BENCH",Instant.EPOCH,Instant.EPOCH,Map.of(2,100)));}
        finally{ORDER_CONTEXT.remove();lock.unlock();}
    }
    void cancel(PurchaseReservationResult result)throws Exception{
        ORDER_CONTEXT.set(result.compensationRequest().getOrderSn()+"-cancel");try{callbacks.cancelCallback(result.compensationRequest());}finally{ORDER_CONTEXT.remove();}
    }
    void drain()throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(db.queryForObject("SELECT COUNT(*) FROM t_inventory_cache_event",Integer.class)>0){if(System.nanoTime()>deadline)throw new IllegalStateException("outbox failed to drain");Thread.sleep(20);}
    }
    void reset()throws Exception{
        failInvalidation=false;BLOCK.set(false);delayMillis=0;trace=false;drain();
        db.update("UPDATE t_seat_segment SET seat_status=0,ticket_id=NULL,order_sn=NULL");db.update("DELETE FROM t_ticket WHERE username=?",USER);
        clearRedis();COMMITS.clear();INVALIDATIONS.clear();EVENT_ORDERS.clear();
    }
    void clearRedis(){
        String prefix=System.getenv("CACHE_TEST_PREFIX");
        try(var connection=redis.getConnectionFactory().getConnection();var cursor=connection.keyCommands().scan(ScanOptions.scanOptions().match(prefix+"*").count(1000).build())){
            var keys=new ArrayList<byte[]>();while(cursor.hasNext()){keys.add(cursor.next());if(keys.size()==1000){connection.keyCommands().del(keys.toArray(byte[][]::new));keys.clear();}}
            if(!keys.isEmpty())connection.keyCommands().del(keys.toArray(byte[][]::new));
        }
    }
    Map<String,Double> meters(){
        var result=new TreeMap<String,Double>();for(Meter meter:metrics.getMeters()){
            if(!meter.getId().getName().startsWith("my12306."))continue;
            String name=meter.getId().getName()+meter.getId().getTags();
            for(Measurement value:meter.measure())result.put(name+"/"+value.getStatistic(),value.getValue());
        }return result;
    }
    Map<String,Double> delta(Map<String,Double> before){var after=meters();after.replaceAll((key,value)->value-before.getOrDefault(key,0d));return after;}
    static final ThreadLocal<Long> HTTP_ELAPSED = new ThreadLocal<>();
    void invoke(String scenario,int worker,long sequence,ArrayDeque<PurchaseReservationResult> owned)throws Exception{
        switch(scenario){
            case "hot-service" -> {if(tickets.pageListTicketQuery(hot()).getTrainList().isEmpty())throw new IllegalStateException("empty hot response");}
            case "hot-http" -> {
                String date=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();var request=HttpRequest.newBuilder(URI.create(baseUrl+"/api/ticket-service/ticket/query?fromStation=VNP&toStation=NGH&departureDate="+date)).GET().timeout(Duration.ofSeconds(20)).build();long sent=System.nanoTime();var response=client.send(request,HttpResponse.BodyHandlers.ofString());HTTP_ELAPSED.set(System.nanoTime()-sent);
                var body=JSON.readTree(response.body());if(response.statusCode()!=200||!body.path("code").asText().equals("0")||body.path("data").path("trainList").isEmpty())throw new IllegalStateException("HTTP query failed");
            }
            case "empty-city" -> {if(!tickets.pageListTicketQuery(emptyPairs.get(0)).getTrainList().isEmpty())throw new IllegalStateException("expected empty city pair");}
            case "cold-cities" -> tickets.pageListTicketQuery(emptyPairs.get(worker%emptyPairs.size()));
            case "missing-train","unique-train" -> {
                var request=new PurchaseTicketReqDTO();request.setTrainId(""+(scenario.equals("unique-train")?9_000_000L+ORDER.incrementAndGet():9_000_000L));request.setDeparture("北京南");request.setArrival("宁波");
                try{purchaseFilter.handler(request);throw new IllegalStateException("nonexistent train accepted");}catch(edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException expected){if(!expected.getMessage().contains("车次"))throw expected;}
            }
            case "empty-search" -> {var request=new RegionStationQueryReqDTO();request.setName("不存在的缓存专项站点");if(!regions.listRegionStation(request).isEmpty())throw new IllegalStateException("expected empty search");}
            case "remaining" -> remaining.load("1",2,"北京南","宁波");
            case "mixed","overlap-mixed" -> {
                int op=(int)((sequence+worker)%10);
                if(op==8){owned.addLast(buy("cache-ab-"+ORDER.incrementAndGet(),"北京南","南京南"));}
                else if(op==9){if(owned.isEmpty())throw new IllegalStateException("unpaired cancellation");cancel(owned.removeFirst());}
                else if(scenario.equals("mixed"))tickets.pageListTicketQuery(hot());
                else {var choices=List.of(new String[]{"北京南","南京南"},new String[]{"北京南","宁波"},new String[]{"南京南","宁波"},new String[]{"杭州东","宁波"});var od=choices.get((int)((sequence+worker)%4));remaining.load("1",2,od[0],od[1]);}
            }
            default -> throw new IllegalArgumentException(scenario);
        }
    }
    static class Samples {
        final ConcurrentHashMap<String,List<Long>> values=new ConcurrentHashMap<>();final ConcurrentHashMap<String,AtomicLong> errors=new ConcurrentHashMap<>();
        void add(String operation,long nanos){values.computeIfAbsent(operation,x->Collections.synchronizedList(new ArrayList<>())).add(nanos);}
        void error(Throwable error){errors.computeIfAbsent(error.getClass().getSimpleName()+":"+error.getMessage(),x->new AtomicLong()).incrementAndGet();}
        Map<String,Object> summary(double seconds){var result=new TreeMap<String,Object>();for(var entry:values.entrySet()){
            var v=new ArrayList<>(entry.getValue());Collections.sort(v);var row=new LinkedHashMap<String,Object>();row.put("success",v.size());row.put("qps",v.size()/seconds);row.put("meanMs",v.stream().mapToLong(Long::longValue).average().orElse(0)/1e6);
            for(double p:new double[]{.50,.95,.99})row.put("p"+(int)(p*100)+"Ms",v.get((int)Math.ceil(p*v.size())-1)/1e6);
            // Keep individual latencies in a compact numeric array, not sampled reservoir estimates.
            row.put("latenciesMicros",v.stream().map(n->n/1000).toList());result.put(entry.getKey(),row);
        }result.put("errors",errors.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,x->x.getValue().get())));return result;}
    }
    void warmHttp(int concurrency,double seconds)throws Exception{
        var ready=new CountDownLatch(concurrency);var go=new CountDownLatch(1);var stop=new AtomicLong();
        try(var pool=Executors.newFixedThreadPool(concurrency)){
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<concurrency;i++){int worker=i;futures.add(pool.submit(()->{ready.countDown();go.await();long sequence=0;
                while(System.nanoTime()<stop.get())invoke("hot-http",worker,sequence++,new ArrayDeque<>());return null;}));}
            ready.await();stop.set(System.nanoTime()+(long)(seconds*1e9));go.countDown();for(var future:futures)future.get((long)seconds+30,TimeUnit.SECONDS);
        }
    }
    Map<String,Object> run(String scenario,int concurrency,double seconds,int round,double httpWarmSeconds)throws Exception{
        reset();tickets.pageListTicketQuery(hot());tickets.pageListTicketQuery(emptyPairs.get(0));
        for(int i=0;i<100;i++)invoke(scenario.equals("mixed")||scenario.equals("overlap-mixed")?"hot-service":scenario,i%concurrency,i,new ArrayDeque<>());
        if(scenario.equals("hot-http")&&httpWarmSeconds>0)warmHttp(concurrency,httpWarmSeconds);
        var samples=new Samples();var before=meters();var ready=new CountDownLatch(concurrency);var start=new CountDownLatch(1);var stop=new AtomicLong();
        var futures=new ArrayList<Future<?>>();var measuredDone=new CountDownLatch(concurrency);var cleanupGo=new CountDownLatch(1);trace=scenario.endsWith("mixed");
        try(var pool=Executors.newFixedThreadPool(concurrency)){
            for(int w=0;w<concurrency;w++){int worker=w;futures.add(pool.submit(()->{
                var owned=new ArrayDeque<PurchaseReservationResult>();
                try{
                    if(scenario.endsWith("mixed"))owned.add(buy("cache-ab-"+ORDER.incrementAndGet(),"北京南","南京南"));
                    ready.countDown();start.await();long sequence=0;
                    while(System.nanoTime()<stop.get()){
                        int op=(int)((sequence+worker)%10);String operation=scenario.endsWith("mixed")?(op==8?"purchase":op==9?"cancel":"query"):"query";
                        long t=System.nanoTime();try(var scope=diagnostics.request()){invoke(scenario,worker,sequence++,owned);samples.add(operation,scenario.equals("hot-http")?HTTP_ELAPSED.get():System.nanoTime()-t);}catch(Exception ex){samples.error(ex);}
                    }
                }catch(Exception ex){samples.error(ex);ready.countDown();}
                // Cleanup commits are outside the measured request loop and its latency distribution.
                finally{measuredDone.countDown();try{cleanupGo.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}for(var ticket:owned)try{cancel(ticket);}catch(Exception ex){samples.error(ex);}}
            }));}
            if(!ready.await(30,TimeUnit.SECONDS))throw new IllegalStateException("workers not ready");
            drain();before=meters();COMMITS.clear();INVALIDATIONS.clear();EVENT_ORDERS.clear();long started=System.nanoTime();stop.set(started+(long)(seconds*1e9));start.countDown();
            if(!measuredDone.await((long)seconds+60,TimeUnit.SECONDS))throw new IllegalStateException("measurement timed out");
            double elapsed=(System.nanoTime()-started)/1e9;drain();var measuredMetrics=delta(before);trace=false;cleanupGo.countDown();
            for(var future:futures)future.get(30,TimeUnit.SECONDS);drain();
            var result=new LinkedHashMap<String,Object>();result.put("model",model);result.put("scenario",scenario);result.put("round",round);result.put("concurrency",concurrency);result.put("configuredSeconds",seconds);result.put("httpWarmSeconds",scenario.equals("hot-http")?httpWarmSeconds:0);result.put("httpTimingScope",scenario.equals("hot-http")?"send-to-body":"service-call");result.put("elapsedSeconds",elapsed);result.put("operations",samples.summary(elapsed));result.put("metrics",measuredMetrics);
            result.put("pendingEvents",db.queryForObject("SELECT COUNT(*) FROM t_inventory_cache_event",Integer.class));
            result.put("activeSegments",db.queryForObject("SELECT COUNT(*) FROM t_seat_segment WHERE seat_status<>0",Integer.class));
            if(scenario.equals("unique-train")){
                int keys=0;String prefix=System.getenv("CACHE_TEST_PREFIX")+TRAIN_INFO;
                try(var connection=redis.getConnectionFactory().getConnection();var cursor=connection.keyCommands().scan(ScanOptions.scanOptions().match(prefix+"*").count(1000).build())){
                    while(cursor.hasNext()){String k=new String(cursor.next(),java.nio.charset.StandardCharsets.UTF_8);if(!k.equals(prefix+"1"))keys++;}
                }
                result.put("remainingNegativeTrainKeys",keys);
            }
            if(scenario.endsWith("mixed")){
                int uncanceled=db.queryForObject("SELECT COUNT(*) FROM t_ticket WHERE username=? AND ticket_status<>30",Integer.class,USER);
                int orphanOwners=db.queryForObject("SELECT COUNT(*) FROM t_seat_segment WHERE seat_status=0 AND (ticket_id IS NOT NULL OR order_sn IS NOT NULL)",Integer.class);
                if(uncanceled!=0||orphanOwners!=0)throw new IllegalStateException("ticket/segment reconciliation failed");
                result.put("uncanceledTickets",uncanceled);result.put("availableSegmentsWithOwner",orphanOwners);
                int versions=0;String versionPrefix=System.getenv("CACHE_TEST_PREFIX")+"my12306-ticket-service:remaining_version:";
                try(var connection=redis.getConnectionFactory().getConnection();var cursor=connection.keyCommands().scan(ScanOptions.scanOptions().match(versionPrefix+"*").count(100).build())){while(cursor.hasNext()){cursor.next();versions++;}}
                result.put("versionKeys",versions);
                if(((Number)result.get("activeSegments")).intValue()!=0)throw new IllegalStateException("mixed inventory leaked");
                var propagation=new ArrayList<Long>();for(var entry:COMMITS.entrySet()){long commit=entry.getValue();
                    long invalidated=INVALIDATIONS.stream().filter(x->entry.getKey().equals(x.order)&&x.time>=commit&&x.keys.contains(key("北京南","宁波"))).mapToLong(Invalidation::time).min().orElse(0);
                    if(invalidated>0)propagation.add(invalidated-commit);
                }
                var latency=new Samples();for(long value:propagation)latency.add("commit-to-invalidation",value);result.put("propagation",latency.summary(elapsed));result.put("commitRecords",COMMITS.size());result.put("matchedInvalidations",propagation.size());
            }
            return result;
        }
    }
    Map<String,Object> burst(String scenario,int concurrency,int waves)throws Exception{
        reset();tickets.pageListTicketQuery(hot());var before=meters();var samples=new Samples();long started=System.nanoTime();
        try(var pool=Executors.newFixedThreadPool(concurrency)){
            for(int wave=0;wave<waves;wave++){
                if(scenario.equals("burst-remaining"))cache.delete(key("北京南","宁波"));
                else if(scenario.equals("burst-city")){var q=emptyPairs.get(0);var names=db.queryForList("SELECT code,name FROM t_region WHERE code IN (?,?)",q.getFromStation(),q.getToStation());cache.delete(String.format(REGION_TRAIN_STATION,names.stream().filter(x->x.get("code").equals(q.getFromStation())).findFirst().orElseThrow().get("name"),names.stream().filter(x->x.get("code").equals(q.getToStation())).findFirst().orElseThrow().get("name")));}
                else clearRedis();
                var ready=new CountDownLatch(concurrency);var go=new CountDownLatch(1);var futures=new ArrayList<Future<?>>();
                for(int w=0;w<concurrency;w++){int worker=w;futures.add(pool.submit(()->{ready.countDown();try{go.await();long t=System.nanoTime();try(var scope=diagnostics.request()){invoke(scenario.equals("burst-remaining")?"remaining":scenario.equals("burst-city")?"empty-city":"cold-cities",worker,0,new ArrayDeque<>());}samples.add("query",System.nanoTime()-t);}catch(Exception ex){samples.error(ex);}}));}
                ready.await();go.countDown();for(var future:futures)future.get(30,TimeUnit.SECONDS);
            }
        }
        double elapsed=(System.nanoTime()-started)/1e9;return Map.of("model",model,"scenario",scenario,"waves",waves,"operations",samples.summary(elapsed),"elapsedSeconds",elapsed,"metrics",delta(before));
    }
    Map<String,Object> race()throws Exception{
        reset();trace=true;captured=new CountDownLatch(1);proceed=new CountDownLatch(1);BLOCK.set(true);
        var pool=Executors.newSingleThreadExecutor();var future=pool.submit(()->remaining.load("1",2,"北京南","宁波"));
        try{
            if(!captured.await(10,TimeUnit.SECONDS))throw new IllegalStateException("snapshot was not captured");
            var ticket=buy("cache-ab-"+ORDER.incrementAndGet(),"北京南","南京南");drain();
            proceed.countDown();var response=future.get(15,TimeUnit.SECONDS);
            String cached=String.valueOf(cache.hGet(key("北京南","宁波"),"2"));
            long truth=((Number)db.queryForObject("SELECT COUNT(*) FROM t_physical_seat p WHERE p.train_id=1 AND p.seat_type=2 AND (SELECT COUNT(*) FROM t_seat_segment s WHERE s.seat_id=p.id AND s.seat_status=0)=4",Long.class)).longValue();
            var result=Map.<String,Object>of("model",model,"scenario","refill-race","cached",cached,"database",truth,"stalePublished",!cached.equals("null")&&Long.parseLong(cached)!=truth,"response",response);
            cancel(ticket);drain();return result;
        }finally{proceed.countDown();BLOCK.set(false);pool.shutdownNow();}
    }
    long truth(String from,String to){
        var range=routes.resolve(1L,from,to);
        return db.queryForObject("SELECT COUNT(*) FROM t_physical_seat p WHERE p.train_id=1 AND p.seat_type=2 AND (SELECT COUNT(*) FROM t_seat_segment s WHERE s.seat_id=p.id AND s.segment_index>=? AND s.segment_index<? AND s.seat_status=0)=?",Long.class,range.start(),range.end(),range.length());
    }
    Map<String,Object> pulses()throws Exception{
        reset();trace=true;var samples=new Samples();var od=List.of(new String[]{"北京南","南京南"},new String[]{"北京南","宁波"},new String[]{"南京南","宁波"});
        for(int i=0;i<10;i++){
            for(var pair:od)remaining.load("1",2,pair[0],pair[1]);
            String sn="cache-ab-"+ORDER.incrementAndGet();var ticket=buy(sn,"北京南","南京南");
            convergence(sn,od,samples);cancel(ticket);convergence(sn+"-cancel",od,samples);
        }
        drain();trace=false;return Map.of("model",model,"scenario","pulses","operations",samples.summary(1),"commits",COMMITS.size(),"activeSegments",db.queryForObject("SELECT COUNT(*) FROM t_seat_segment WHERE seat_status<>0",Integer.class));
    }
    void convergence(String sn,List<String[]> od,Samples samples)throws Exception{
        Long committed=COMMITS.get(sn);if(committed==null)throw new IllegalStateException("commit hook not invoked");
        var expected=new ArrayList<Long>();for(var pair:od)expected.add(truth(pair[0],pair[1]));int correct=0;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){
            boolean equal=true;for(int i=0;i<od.size();i++){var pair=od.get(i);equal&=Long.parseLong(remaining.load("1",2,pair[0],pair[1]).get("2"))==expected.get(i);}
            correct=equal?correct+1:0;if(correct==3){samples.add("commit-to-correct",System.nanoTime()-committed);return;}Thread.sleep(5);
        }
        samples.error(new IllegalStateException("convergence timeout"));throw new IllegalStateException("convergence timeout");
    }
    Map<String,Object> checks()throws Exception{
        reset();var passed=new ArrayList<String>();
        var policy=context.getBean(CacheTtlPolicy.class);var negative=policy.getClass().getDeclaredField("negative");negative.setAccessible(true);negative.setLong(policy,1);
        String absent=TRAIN_INFO+"semantic-negative";var calls=new AtomicInteger();
        for(int i=0;i<50;i++)if(cache.safeGet(absent,()->{calls.incrementAndGet();return null;},10,TimeUnit.SECONDS)!=null)throw new IllegalStateException("negative leaked");
        if(calls.get()!=1)throw new IllegalStateException("negative did not merge");Thread.sleep(1100);
        if(!"created".equals(cache.safeGet(absent,()->"created",10,TimeUnit.SECONDS)))throw new IllegalStateException("negative expiry hid new data");negative.setLong(policy,30);passed.add("negative-expiry-rediscovers-data");
        negative.setLong(policy,1);var hashCalls=new AtomicInteger();String emptyHash=String.format(REGION_TRAIN_STATION,"semantic","empty");
        for(int i=0;i<10;i++)if(!cache.loadHash(emptyHash,"semantic-hash-lock",()->{hashCalls.incrementAndGet();return Map.of();},false).isEmpty())throw new IllegalStateException("empty marker leaked");
        if(hashCalls.get()!=1)throw new IllegalStateException("empty hash not merged");Thread.sleep(1100);
        if(!cache.loadHash(emptyHash,"semantic-hash-lock",()->Map.of("route","created"),false).equals(Map.of("route","created")))throw new IllegalStateException("empty hash expiry hid new route");
        negative.setLong(policy,30);passed.add("negative-hash-expiry-rediscovers-data");
        String failure=TRAIN_INFO+"semantic-failure";try{cache.safeGet(failure,()->{throw new org.springframework.dao.QueryTimeoutException("test-only timeout");},10,TimeUnit.SECONDS);throw new IllegalStateException("timeout swallowed");}catch(org.springframework.dao.QueryTimeoutException expected){}
        if(redis.hasKey(failure))throw new IllegalStateException("timeout cached");passed.add("database-timeout-not-cached");
        var connection=(LettuceConnectionFactory)redis.getConnectionFactory();connection.stop();
        try{cache.safeGet(failure,()->"unexpected",10,TimeUnit.SECONDS);throw new IllegalStateException("Redis fault swallowed");}catch(IllegalStateException expected){if(expected.getMessage().contains("swallowed"))throw expected;}finally{connection.start();}
        if(redis.hasKey(failure))throw new IllegalStateException("Redis fault cached");passed.add("Redis-client-outage-not-negative");
        String corrupt=TRAIN_INFO+"99000001";cache.put(corrupt,"broken{",10,TimeUnit.SECONDS);
        try{cache.train("99000001",context.getBean(edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper.class));throw new IllegalStateException("parse fault swallowed");}catch(com.alibaba.fastjson2.JSONException expected){}
        if(!"broken{".equals(redis.opsForValue().get(corrupt)))throw new IllegalStateException("parse fault cached as absent");passed.add("parse-error-not-negative");
        String k=key("北京南","宁波");String v=cache.remainingVersion(k);cache.invalidateRemaining(List.of(k));
        if(cache.publishRemaining(k,v,Map.of("2","999"),30))throw new IllegalStateException("obsolete nonce accepted");
        String newer=cache.remainingVersion(k);cache.invalidateRemaining(List.of(k));String newest=cache.remainingVersion(k);
        if(v.equals(newer)||newer.equals(newest))throw new IllegalStateException("duplicate invalidation reused nonce");
        redis.delete(cache.versionKey(k));if(cache.publishRemaining(k,newest,Map.of("2","999"),30))throw new IllegalStateException("missing nonce accepted");passed.add("changed-missing-duplicate-nonce-rejected");
        cache.publishSnapshot("semantic-snapshot",Map.of("old","1","second","1"));cache.publishSnapshot("semantic-snapshot",Map.of("new","2"));
        if(!cache.hGetAll("semantic-snapshot").equals(Map.of("new","2"))||redis.getExpire("semantic-snapshot")<=0)throw new IllegalStateException("partial snapshot");passed.add("hash-replacement-finite-TTL");
        remaining.load("1",2,"南京南","宁波");remaining.load("1",2,"北京南","宁波");
        var ticket=buy("cache-ab-"+ORDER.incrementAndGet(),"北京南","南京南");drain();
        if(redis.hasKey(k)||!redis.hasKey(key("南京南","宁波")))throw new IllegalStateException("overlap invalidation incorrect");cancel(ticket);drain();passed.add("overlap-deleted-adjacent-preserved");
        remaining.load("1",2,"北京南","宁波");failInvalidation=true;ticket=buy("cache-ab-"+ORDER.incrementAndGet(),"北京南","南京南");Thread.sleep(300);
        if(db.queryForObject("SELECT COUNT(*) FROM t_inventory_cache_event",Integer.class)==0)throw new IllegalStateException("failed event lost");
        failInvalidation=false;context.getBean(InventoryCacheEventProcessor.class).retry();drain();
        if(Long.parseLong(remaining.load("1",2,"北京南","宁波").get("2"))!=truth("北京南","宁波"))throw new IllegalStateException("retry did not converge");cancel(ticket);drain();passed.add("outbox-retry-converges");
        String legacy=String.format(REGION_TRAIN_STATION,"legacy","permanent");redis.opsForHash().put(legacy,"route","{}");
        var audit=edu.swu.fcj.my12306.biz.ticketservice.toolkit.CacheTtlMigrationTool.repair(redis,policy,false,30);
        if(audit.permanentKeys()!=1||audit.repairedKeys()!=0||redis.getExpire(legacy)!=-1)throw new IllegalStateException("legacy dry run modified TTL");
        var repaired=edu.swu.fcj.my12306.biz.ticketservice.toolkit.CacheTtlMigrationTool.repair(redis,policy,true,30);
        if(repaired.repairedKeys()!=1||redis.getExpire(legacy)<269||redis.getExpire(legacy)>330)throw new IllegalStateException("legacy TTL repair failed");
        if(edu.swu.fcj.my12306.biz.ticketservice.toolkit.CacheTtlMigrationTool.repair(redis,policy,true,30).repairedKeys()!=0)throw new IllegalStateException("legacy repair extended an existing TTL");
        passed.add("legacy-permanent-TTL-audit-and-idempotent-repair");
        var race=race();if(Boolean.TRUE.equals(race.get("stalePublished")))throw new IllegalStateException("stale refill");passed.add("deterministic-refill-race");
        return Map.of("model",model,"scenario","checks","passed",passed,"race",race);
    }
    Map<String,Object> expiry()throws Exception{
        reset();int count=100;var bins=new TreeMap<Long,Integer>();
        var mapper=context.getBean(edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper.class);
        String value=com.alibaba.fastjson2.JSON.toJSONString(mapper.selectById(1L));
        for(int i=0;i<count;i++)cache.put(TRAIN_INFO+"ttl-probe-"+i,value,model.equals("B")?context.getBean(CacheTtlPolicy.class).positive(10):10,TimeUnit.SECONDS);
        for(int i=0;i<count;i++){long ms=redis.getExpire(TRAIN_INFO+"ttl-probe-"+i,TimeUnit.MILLISECONDS);bins.merge(ms/1000,1,Integer::sum);}
        var loads=new ConcurrentSkipListMap<Long,AtomicInteger>();var ready=new CountDownLatch(50);var go=new CountDownLatch(1);
        var started=new AtomicLong();var requests=new AtomicLong();var before=meters();
        try(var pool=Executors.newFixedThreadPool(50)){
            var futures=new ArrayList<Future<?>>();
            for(int w=0;w<50;w++){int worker=w;futures.add(pool.submit(()->{ready.countDown();go.await();long sequence=0;
                while(System.nanoTime()<started.get()+TimeUnit.SECONDS.toNanos(15)){
                    String key=TRAIN_INFO+"ttl-probe-"+((sequence++ + worker)%count);
                    String json=cache.safeGet(key,()->{
                        loads.computeIfAbsent((System.nanoTime()-started.get())/100000000,x->new AtomicInteger()).incrementAndGet();
                        return com.alibaba.fastjson2.JSON.toJSONString(mapper.selectById(1L));
                    },10,TimeUnit.SECONDS);
                    if(json==null)throw new IllegalStateException("positive TTL probe became absent");requests.incrementAndGet();
                }return null;}));}
            ready.await();started.set(System.nanoTime());go.countDown();for(var future:futures)future.get(30,TimeUnit.SECONDS);
        }
        var sqlBins=new TreeMap<Long,Integer>();loads.forEach((k,v)->sqlBins.put(k*100,v.get()));
        return Map.of("model",model,"scenario","expiry","syntheticKeysWithRealTrainData",count,"initialTtlBinsSeconds",bins,
                "rebuildSqlPer100Ms",sqlBins,"queries",requests.get(),"metrics",delta(before),"durationSeconds",15);
    }
    Map<String,Object> parallelProof()throws Exception{
        reset();tickets.pageListTicketQuery(hot());relationPeak.set(0);relationActive.set(0);relationPauseMillis=200;
        long started=System.nanoTime();var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{go.await();return tickets.pageListTicketQuery(emptyPairs.get(0));});
            var b=pool.submit(()->{go.await();return tickets.pageListTicketQuery(emptyPairs.get(1));});go.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }finally{relationPauseMillis=0;}
        return Map.of("model",model,"scenario","parallel-proof","injectedDelayMs",200,"peakConcurrentCityLoads",relationPeak.get(),"elapsedMs",(System.nanoTime()-started)/1e6);
    }
    public static void main(String[] args)throws Exception{
        String model=args.length==0?"A":args[0];
        try(var context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(TicketServicesApplication.class,Config.class).properties(
                "server.port=0","spring.cloud.nacos.discovery.enabled=false","spring.cloud.discovery.enabled=false","my12306.pay.notify-mode=feign",
                "my12306.ticket.orphan-scan-enabled=false","my12306.inventory.cache-events.enabled=true","spring.main.allow-bean-definition-overriding=true",
                "spring.datasource.hikari.maximum-pool-size=20","logging.level.edu.swu.fcj.my12306.biz.ticketservice.service.impl.PurchaseTicketServiceImpl=WARN").run()){
            var driver=new CacheBenchmarkMain(context,model);System.out.println("CACHE_AB_READY "+driver.baseUrl);
            var input=new BufferedReader(new InputStreamReader(System.in));String line;
            while((line=input.readLine())!=null){
                var command=JSON.readTree(line);String scenario=command.path("scenario").asText();if(scenario.equals("exit"))break;
                try{
                    Map<String,Object> result=scenario.equals("parallel-proof")?driver.parallelProof():scenario.equals("checks")?driver.checks():scenario.equals("pulses")?driver.pulses():scenario.equals("expiry")?driver.expiry():scenario.equals("refill-race")?driver.race():scenario.startsWith("burst")?driver.burst(scenario,command.path("concurrency").asInt(50),command.path("waves").asInt(30)):
                            driver.run(scenario,command.path("concurrency").asInt(50),command.path("seconds").asDouble(60),command.path("round").asInt(0),command.path("httpWarmSeconds").asDouble(10));
                    System.out.println("CACHE_AB_RESULT "+JSON.writeValueAsString(result));
                }catch(Throwable ex){ex.printStackTrace();System.out.println("CACHE_AB_ERROR "+JSON.writeValueAsString(Map.of("scenario",scenario,"error",ex.toString())));}
            }
            driver.reset();driver.client.close();
        }
    }
}
