package perfdiag;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Opt-in request-scoped diagnostic agent; no business classes are edited. */
public class StageAgent {
    public static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    public static final BlockingQueue<Trace> QUEUE = new ArrayBlockingQueue<>(2000);
    public static final AtomicLong IDS = new AtomicLong(), DROPPED = new AtomicLong();
    public static volatile boolean capture, running=true;
    public static Path output, flag;
    public static class Trace {
        public long id=IDS.incrementAndGet(), start=System.nanoTime(), wall=System.currentTimeMillis();
        public String thread=Thread.currentThread().getName();
        public Map<String,long[]> stages=new LinkedHashMap<>();
        public long end, seatAcquire, unlockStart, unlockEnd; public boolean failed;
        public List<LockEvent> locks = new ArrayList<>();
    }
    public record LockEvent(String key,String stage,long start,long end,boolean failed) { }
    public static class Span {
        public final Trace trace; public final String stage; public final long start;
        public String lockKey;
        public Span(Trace t,String s){trace=t;stage=s;start=System.nanoTime();}
    }
    public static void premain(String args, Instrumentation instrumentation) throws Exception {
        if(args.startsWith("b64:"))args=new String(Base64.getDecoder().decode(args.substring(4)),java.nio.charset.StandardCharsets.UTF_8);
        Path dir=Paths.get(args);Files.createDirectories(dir);output=dir.resolve("traces.csv");flag=dir.resolve("capture.flag");
        Thread writer=new Thread(StageAgent::writeLoop,"purchase-stage-writer");writer.setDaemon(true);writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{running=false;try{writer.join(3000);}catch(InterruptedException ignored){}}));
        String p="edu.swu.fcj.my12306.biz.ticketservice.";
        new AgentBuilder.Default().disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
            .type(namedOneOf(p+"service.impl.PurchaseTicketServiceImpl",p+"service.impl.PurchaseTicketTxService",
                p+"service.handler.ticket.seat.SeatAllocator",p+"service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket",
                p+"common.chain.AbstractChainContext","org.redisson.RedissonBaseLock","org.redisson.RedissonLock",
                "com.mysql.cj.jdbc.ClientPreparedStatement","com.mysql.cj.jdbc.ConnectionImpl",
                "com.zaxxer.hikari.HikariDataSource","org.springframework.transaction.interceptor.TransactionInterceptor"))
            .transform((b,t,l,m,d)->b.visit(Advice.to(StageAdvice.class).on(isMethod().and(not(isAbstract())).and(
                namedOneOf("purchaseTickets","reserveLocally","loadPassengers","createOrder","evictRemainingTicketCacheSafely",
                  "unlockSafely","reserveWithCarriageLocks","doPurchaseInTransaction","loadTrain","queryTicketAmount","allocate","allocateInCarriage","takeToken","handler",
                  "lock","unlock","isHeldByCurrentThread","execute","executeQuery","executeUpdate","commit","setAutoCommit",
                  "getConnection","invoke")))))
            .installOn(instrumentation);
        System.err.println("Purchase stage agent installed; capture controlled by local flag.");
    }
    public static Span enter(Object self,String type,String method,Object[] args) throws Exception {
        Trace t=CURRENT.get();
        if(method.equals("purchaseTickets")&&type.endsWith("PurchaseTicketServiceImpl")){
            if(!capture || t!=null)return null;
            t=new Trace();CURRENT.set(t);return new Span(t,"purchase.total");
        }
        if(t==null)return null;
        String stage=null, lockKey=null;
        if(type.contains("PurchaseTicketServiceImpl"))stage=switch(method){
            case "reserveLocally"->"reservation.total";case "loadPassengers"->"passenger.remote";
            case "createOrder"->"order.remote";case "evictRemainingTicketCacheSafely"->"remaining-cache.evict";
            case "reserveWithCarriageLocks"->"carriage.attempt-total";
            case "unlockSafely"->("席别锁".equals(args[1])||"车厢锁".equals(args[1]))?"seat.unlock-safe":"user.unlock-safe";default->null;};
        else if(type.contains("PurchaseTicketTxService"))stage=switch(method){
            case "doPurchaseInTransaction"->"tx.body";case "loadTrain"->"train.cache";
            case "queryTicketAmount"->"price.lookup";default->null;};
        else if(type.endsWith("SeatAllocator")&&(method.equals("allocate")||method.equals("allocateInCarriage")))stage="seat.allocate";
        else if(type.endsWith("TicketAvailabilityTokenBucket")&&method.equals("takeToken"))stage="token.admission";
        else if(type.endsWith("AbstractChainContext")&&method.equals("handler"))stage="chain.validation";
        else if(type.contains("TransactionInterceptor")&&method.equals("invoke")){
            Class<?> invocation=Class.forName("org.aopalliance.intercept.MethodInvocation",false,args[0].getClass().getClassLoader());
            Object targetMethod=invocation.getMethod("getMethod").invoke(args[0]);
            if(((java.lang.reflect.Method)targetMethod).getName().equals("doPurchaseInTransaction"))stage="tx.proxy";
        } else if(type.contains("Redisson")){
            if(args.length!=0)return null;
            String name=(String)self.getClass().getMethod("getName").invoke(self);
            boolean user=name.contains("purchase_tickets_user_");
            if(!user&&!name.contains("lock:purchase_tickets_"))return null;
            lockKey=name;
            String prefix=user?"user":"seat";
            stage=switch(method){case "lock"->prefix+".lock-wait";case "unlock"->prefix+".unlock-rpc";
                case "isHeldByCurrentThread"->prefix+".ownership-check";default->null;};
        } else if(type.endsWith("HikariDataSource")&&method.equals("getConnection")&&args.length==0)stage="jdbc.connection";
        else if(type.endsWith("ConnectionImpl"))stage=switch(method){
            case "commit"->"jdbc.commit";case "setAutoCommit"->"jdbc.autocommit";default->null;};
        else if(type.endsWith("ClientPreparedStatement")&&args.length==0){
            String sql=((String)self.getClass().getMethod("getPreparedSql").invoke(self)).replace("`","").toLowerCase(Locale.ROOT);
            if(sql.contains("from t_seat"))stage=sql.contains("distinct carriage_number")?"jdbc.carriage-directory":"jdbc.seat-select";
            else if(sql.contains("update t_seat"))stage="jdbc.seat-update";
            else if(sql.contains("into t_ticket"))stage="jdbc.ticket-insert";
            else if(sql.contains("t_train_station_relation"))stage="jdbc.station-relation";
            else if(sql.contains("t_train_station_price"))stage="jdbc.price";
            else if(sql.contains("t_train_station"))stage="jdbc.train-stations";
            else stage="jdbc.other";
        }
        if(stage==null)return null;
        Span span=new Span(t,stage);
        if(stage.equals("seat.unlock-safe"))lockKey=(String)args[0].getClass().getMethod("getName").invoke(args[0]);
        span.lockKey=lockKey;
        return span;
    }
    public static void exit(Span s,Throwable error){
        if(s==null)return;long end=System.nanoTime();Trace t=s.trace;
        long[] value=t.stages.computeIfAbsent(s.stage,k->new long[]{0,0,s.start,end});value[0]+=end-s.start;value[1]++;value[3]=end;
        if(s.lockKey!=null)t.locks.add(new LockEvent(s.lockKey,s.stage,s.start,end,error!=null));
        if(s.stage.equals("seat.lock-wait")&&error==null)t.seatAcquire=end;
        if(s.stage.equals("seat.unlock-safe")){t.unlockStart=s.start;t.unlockEnd=end;}
        if(s.stage.equals("purchase.total")){
            t.end=end;t.failed=error!=null;CURRENT.remove();
            if(!QUEUE.offer(t))DROPPED.incrementAndGet();
        }
    }
    public static class StageAdvice {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static Span before(@Advice.This(optional=true) Object self,@Advice.Origin("#t") String type,
            @Advice.Origin("#m") String method,@Advice.AllArguments Object[] args)throws Exception{
            return StageAgent.enter(self,type,method,args);
        }
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class)
        public static void after(@Advice.Enter Span span,@Advice.Thrown Throwable error){StageAgent.exit(span,error);}
    }
    public static void writeLoop(){
        try(BufferedWriter w=Files.newBufferedWriter(output);
            BufferedWriter locks=Files.newBufferedWriter(output.resolveSibling("locks.csv"))){
            w.write("id,thread,wallMs,startNs,endNs,seatAcquireNs,unlockStartNs,unlockEndNs,failed,stage,durationNs,count,stageStartNs,stageEndNs\n");
            locks.write("id,key,stage,startNs,endNs,failed\n");
            while(running||!QUEUE.isEmpty()){
                capture=Files.exists(flag);Trace t=QUEUE.poll(100,TimeUnit.MILLISECONDS);
                if(t==null){w.flush();locks.flush();continue;}
                for(var e:t.stages.entrySet())w.write(t.id+","+t.thread+","+t.wall+","+t.start+","+t.end+","+
                    t.seatAcquire+","+t.unlockStart+","+t.unlockEnd+","+t.failed+","+e.getKey()+","+e.getValue()[0]+","+e.getValue()[1]+","+e.getValue()[2]+","+e.getValue()[3]+"\n");
                for(LockEvent e:t.locks)locks.write(t.id+","+e.key()+","+e.stage()+","+e.start()+","+e.end()+","+e.failed()+"\n");
            }
            w.flush();Files.writeString(output.resolveSibling("dropped.txt"),Long.toString(DROPPED.get()));
        }catch(Exception error){System.err.println("Purchase stage writer failed: "+error.getClass().getSimpleName());}
    }
}
