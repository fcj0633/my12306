package perfdiag;

import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import net.minidev.json.JSONValue;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.time.Duration;
import java.lang.management.*;
import java.util.*;

/** A single JVM and client survive every phase and inventory cleanup. */
public class WarmPurchaseSampler extends AbstractJavaSamplerClient {
    static final Object MONITOR=new Object();
    static volatile Phase phase;
    static volatile boolean stopping;
    static HttpClient client;
    static Path dir;
    static BufferedWriter records;
    static final java.util.concurrent.atomic.AtomicInteger tornDown=new java.util.concurrent.atomic.AtomicInteger();
    static List<String[]> users;
    static class Phase {
        String id,url;int level,perWorker,cap,sent,arrived,retired;
        double seconds;long startNs,startMs;
        boolean capReached;boolean[] seen=new boolean[100],finished=new boolean[100];int[] counts=new int[100];
    }
    static Number number(Map<?,?> map,String key){return (Number)map.get(key);}
    static synchronized void initialize() throws Exception {
        if(client!=null)return;
        dir=Paths.get(new String(Base64.getDecoder().decode(JMeterUtils.getProperty("warm.dir")),StandardCharsets.UTF_8));
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        records=Files.newBufferedWriter(dir.resolve("client-requests.csv"));
        records.write("phase,worker,requestId,sendMs,completeMs,httpNs,classifyNs,status,classification,orderSn\n");records.flush();
        Thread controller=new Thread(()->{
            String previous="";
            while(!stopping){
                try {
                    Path command=dir.resolve("command.json");
                    if(Files.exists(command)){
                        Map<?,?> map=(Map<?,?>)JSONValue.parse(Files.readString(command));
                        String id=String.valueOf(map.get("id"));
                        if(!id.equals(previous)){
                            previous=id;
                            synchronized(MONITOR){
                                if(Boolean.TRUE.equals(map.get("stop"))){stopping=true;MONITOR.notifyAll();break;}
                                Phase p=new Phase();p.id=id;p.url=String.valueOf(map.get("url"));
                                p.level=number(map,"level").intValue();p.perWorker=number(map,"perWorker").intValue();
                                p.cap=number(map,"cap").intValue();p.seconds=number(map,"seconds").doubleValue();
                                users=new ArrayList<>();for(String line:Files.readAllLines(dir.resolve("users.csv"),StandardCharsets.UTF_8).subList(1,401))users.add(line.split(",",3));
                                phase=p;MONITOR.notifyAll();
                            }
                        }
                    }
                    MemoryUsage heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                    String sample="{\"time\":"+System.currentTimeMillis()+",\"used\":"+heap.getUsed()+",\"max\":"+heap.getMax()+",\"gcCount\":"+ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionCount())).sum()+"}\n";
                    Files.writeString(dir.resolve("injector-resources.jsonl"),sample,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    Thread.sleep(100);
                } catch(Exception e){
                    try{Files.writeString(dir.resolve("injector-error.txt"),e.toString());}catch(Exception ignored){}
                    stopping=true;synchronized(MONITOR){MONITOR.notifyAll();}
                }
            }
        },"warm-controller");controller.setDaemon(true);controller.start();
    }
    static void retire(Phase p,int worker) throws Exception {
        if(!p.finished[worker]){p.finished[worker]=true;p.retired++;}
        if(p.retired==p.level){
            records.flush();
            String result="{\"id\":\""+p.id+"\",\"sent\":"+p.sent+",\"startMs\":"+p.startMs+",\"startNs\":"+p.startNs+",\"doneMs\":"+System.currentTimeMillis()+",\"capReached\":"+p.capReached+"}";
            Files.writeString(dir.resolve(p.id+"-done.json"),result);
        }
    }
    @Override public SampleResult runTest(JavaSamplerContext context){
        SampleResult sample=new SampleResult();
        try {
            initialize();int worker=JMeterContextService.getContext().getThreadNum();Phase p;
            synchronized(MONITOR){
                while(true){
                    if(stopping){sample.setIgnore();JMeterContextService.getContext().getThread().stop();return sample;}
                    p=phase;
                    if(p==null||worker>=p.level||p.finished[worker]){MONITOR.wait(100);continue;}
                    if(!p.seen[worker]){
                        p.seen[worker]=true;p.arrived++;
                        if(p.arrived==p.level){p.startNs=System.nanoTime();p.startMs=System.currentTimeMillis();MONITOR.notifyAll();}
                    }
                    if(p.startNs==0){MONITOR.wait(100);continue;}
                    if((p.perWorker>0&&p.counts[worker]>=p.perWorker)||
                       (p.perWorker==0&&(System.nanoTime()-p.startNs)/1e9>=p.seconds)){
                        retire(p,worker);MONITOR.wait(100);continue;
                    }
                    if(p.sent>=p.cap){p.capReached=true;retire(p,worker);MONITOR.wait(100);continue;}
                    p.sent++;p.counts[worker]++;break;
                }
            }
            String requestId=p.id+"-"+worker+"-"+p.counts[worker];String[] user=users.get(worker);
            String body="{\"trainId\":\"1\",\"departure\":\"北京南\",\"arrival\":\"宁波\",\"chooseSeats\":[],\"passengers\":[{\"passengerId\":\""+user[2]+"\",\"seatType\":2}]}";
            HttpRequest request=HttpRequest.newBuilder(URI.create(p.url)).timeout(Duration.ofSeconds(120))
                .header("Content-Type","application/json; charset=utf-8").header("Authorization",user[1])
                .header("X-Perf-Request-Id",requestId).POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
            int status=0;String response="";String classification="ERR_TRANSPORT",orderSn="";
            long sendMs=System.currentTimeMillis(),start=System.nanoTime(),end;
            sample.setSampleLabel(p.id);sample.sampleStart();
            try{HttpResponse<String> reply=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));status=reply.statusCode();response=reply.body();}
            catch(Exception e){response=e.getClass().getSimpleName();}
            finally{end=System.nanoTime();sample.sampleEnd();}
            long classifyStart=System.nanoTime();
            try{
                Map<?,?> parsed=(Map<?,?>)JSONValue.parse(response);
                classification=status==200&&"0".equals(String.valueOf(parsed.get("code")))?"OK":"ERR_BUSINESS";
                if(parsed.get("data") instanceof Map<?,?> data&&data.get("orderSn")!=null)orderSn=String.valueOf(data.get("orderSn"));
            }catch(Exception e){classification="ERR_PARSE";}
            long classifyNs=System.nanoTime()-classifyStart;
            sample.setResponseCode(String.valueOf(status));sample.setResponseMessage(classification);sample.setSuccessful("OK".equals(classification));
            sample.setResponseData(response,StandardCharsets.UTF_8.name());
            synchronized(MONITOR){records.write(p.id+","+worker+","+requestId+","+sendMs+","+(sendMs+(end-start)/1_000_000)+","+(end-start)+","+classifyNs+","+status+","+classification+","+orderSn+"\n");}
        }catch(Exception e){sample.setIgnore();try{Files.writeString(dir.resolve("injector-error.txt"),e.toString());}catch(Exception ignored){}}
        return sample;
    }
    @Override public void teardownTest(JavaSamplerContext context){
        if(tornDown.incrementAndGet()==100){
            try{synchronized(MONITOR){records.flush();Files.writeString(dir.resolve("injector-stopped.flag"),"all workers completed");}}catch(Exception ignored){}
        }
    }
}
