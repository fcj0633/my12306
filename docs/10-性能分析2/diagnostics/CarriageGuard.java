import java.nio.file.*;
import java.util.*;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.config.Config;

/** External JVM holds the exact resource keys while two Ticket instances attempt to purchase. */
public class CarriageGuard {
    public static void main(String[] args) throws Exception {
        Path directory=Paths.get(new String(Base64.getDecoder().decode(args[0]),java.nio.charset.StandardCharsets.UTF_8));
        Config config=new Config();config.setNettyThreads(2);config.setThreads(2);
        config.useSingleServer().setAddress("redis://192.168.204.128:6379")
                .setPassword(System.getenv("BENCH_REDIS_PASSWORD"))
                .setConnectionPoolSize(4).setConnectionMinimumIdleSize(1);
        RedissonClient redis=Redisson.create(config);List<RLock> acquired=new ArrayList<>();
        try {
            for(int i=7;i<=15;i++){
                RLock lock=redis.getLock("my12306-ticket-service:lock:purchase_tickets_carriage_1_2_"+String.format("%02d",i));
                lock.lock();acquired.add(lock);
            }
            Files.writeString(directory.resolve("guard-ready"),"ready");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while(!Files.exists(directory.resolve("guard-release"))&&System.nanoTime()<deadline)Thread.sleep(50);
        } finally {
            for(int i=acquired.size()-1;i>=0;i--)acquired.get(i).unlock();
            redis.shutdown();
        }
    }
}
