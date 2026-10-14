package edu.swu.fcj.my12306.biz.ticketservice.common.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.ThreadLocalRandom;

/** TTLs are seconds; remaining inventory never exceeds its configured upper bound. */
@Component
public class CacheTtlPolicy {
    @Value("${my12306.cache.negative-seconds:30}") private long negative=30;
    @Value("${my12306.cache.city-pair-seconds:300}") private long cityPair=300;
    @Value("${my12306.cache.snapshot-seconds:86400}") private long snapshot=86400;
    @Value("${my12306.cache.long-seconds:1296000}") private long longTtl=1296000;
    @Value("${my12306.cache.version-seconds:120}") private long version=120;
    public long negative(){return Math.max(1,negative);}
    public long cityPair(){return jitter(cityPair);}
    public long snapshot(){return jitter(snapshot);}
    public long version(){return Math.max(1,version);}
    public long positive(long requested){return jitter(requested>=86400?longTtl:requested);}
    public long remaining(long ceiling){long upper=Math.max(1,ceiling);return ThreadLocalRandom.current().nextLong(Math.max(1,(long)Math.ceil(upper*.9)),upper+1);}
    private long jitter(long base){base=Math.max(1,base);return ThreadLocalRandom.current().nextLong(Math.max(1,(long)Math.floor(base*.9)),Math.max(2,(long)Math.ceil(base*1.1)+1));}
}
