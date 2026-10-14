package edu.swu.fcj.my12306.biz.ticketservice.common.cache;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 模块内轻量缓存助手：只封装「读缓存 → 回源 → 写缓存」与常用 hash 操作、分布式锁执行。
 * <p>
 * 设计取舍：不新造缓存框架层，保持与 user-services 一致的直连风格；
 * 正常空结果有短 TTL；加载异常继续向调用方传播。
 */
@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redissonClient;
    private CacheDiagnostics diagnostics=CacheDiagnostics.noop();
    private CacheTtlPolicy ttl=new CacheTtlPolicy();
    private static final String EMPTY="\u0000my12306:empty:v1";
    private static final String EMPTY_FIELD="__my12306_empty_v1__";
    private static final String VERSION_PREFIX="my12306-ticket-service:remaining_version:";
    @org.springframework.beans.factory.annotation.Autowired
    public void setTtlPolicy(CacheTtlPolicy policy){ttl=policy;}
    @org.springframework.beans.factory.annotation.Autowired
    public void setDiagnostics(CacheDiagnostics diagnostics){this.diagnostics=diagnostics;}
    public <T> T diagnosed(String key,Supplier<T> action){return diagnostics.loading(key,action);}

    public String get(String key) {
        String value=stringRedisTemplate.opsForValue().get(key);
        diagnostics.lookup(key,key,value==null?"miss":EMPTY.equals(value)?"negative":"hit");return value;
    }

    public void put(String key, String value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /**
     * 先读缓存，未命中按 Key 加锁双检；正常空结果使用短 TTL 标记。
     */
    public String safeGet(String key, Supplier<String> loader, long timeout, TimeUnit unit) {
        String value = get(key);
        if (value!=null) return EMPTY.equals(value)?null:value;
        var result=new java.util.concurrent.atomic.AtomicReference<String>();
        executeWithLock("my12306-ticket-service:lock:cache-load:"+key,()->{
            String current=get(key);
            if(current!=null){result.set(EMPTY.equals(current)?null:current);return;}
            String loaded=diagnosed(key,loader);
            boolean empty=StrUtil.isBlank(loaded);
            put(key,empty?EMPTY:loaded,empty?ttl.negative():ttl.positive(unit.toSeconds(timeout)),TimeUnit.SECONDS);
            result.set(empty?null:loaded);
        });
        return result.get();
    }

    /** Shared train metadata entry for query, validation, preparation and remaining inventory. */
    public edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainDO train(String id,
            edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper mapper){
        String json=safeGet(edu.swu.fcj.my12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_INFO+id,()->{
            var train=mapper.selectById(Long.valueOf(id));
            return train==null?null:com.alibaba.fastjson2.JSON.toJSONString(train);
        },15,TimeUnit.DAYS);
        return json==null?null:com.alibaba.fastjson2.JSON.parseObject(json,edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainDO.class);
    }

    /** A complete hash, including an empty snapshot, is published in one Redis operation. */
    public Map<Object,Object> loadHash(String key,String lockKey,Supplier<Map<Object,Object>> loader,boolean snapshot){
        Map<Object,Object> hit=hGetAll(key);
        if(!hit.isEmpty())return decodeHash(hit);
        var result=new java.util.concurrent.atomic.AtomicReference<Map<Object,Object>>();
        executeWithLock(lockKey,()->{
            Map<Object,Object> second=hGetAll(key);
            if(!second.isEmpty()){result.set(decodeHash(second));return;}
            Map<Object,Object> loaded=diagnosed(key,loader);
            // Null is not an empty snapshot: it is a loader contract violation.
            java.util.Objects.requireNonNull(loaded,"hash loader must return a snapshot");
            hPutAllWithTtl(key,loaded.isEmpty()?Map.of(EMPTY_FIELD,"1"):loaded,
                    loaded.isEmpty()?ttl.negative():snapshot?ttl.snapshot():ttl.cityPair(),TimeUnit.SECONDS);
            result.set(loaded);
        });
        return result.get();
    }
    private Map<Object,Object> decodeHash(Map<Object,Object> value){return value.containsKey(EMPTY_FIELD)?Map.of():value;}
    public long snapshotTtl(){return ttl.snapshot();}
    public long negativeTtl(){return ttl.negative();}
    public long remainingTtl(long upper){return ttl.remaining(upper);}
    public void publishSnapshot(String key,Map<Object,Object> values){
        hPutAllWithTtl(key,values.isEmpty()?Map.of(EMPTY_FIELD,"1"):values,values.isEmpty()?ttl.negative():ttl.snapshot(),TimeUnit.SECONDS);
    }
    public String versionKey(String key){return VERSION_PREFIX+key.substring(key.indexOf("train_station_remaining_ticket:")+"train_station_remaining_ticket:".length());}
    public String remainingVersion(String key){
        var script=new org.springframework.data.redis.core.script.DefaultRedisScript<String>(
                "local v=redis.call('GET',KEYS[1]); if v then return v end; redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[2]); return ARGV[1]",String.class);
        return stringRedisTemplate.execute(script,List.of(versionKey(key)),java.util.UUID.randomUUID().toString(),String.valueOf(ttl.version()));
    }
    public boolean publishRemaining(String key,String version,Map<Object,Object> values,long seconds){
        var script=new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "if redis.call('GET',KEYS[2])~=ARGV[1] then return 0 end; redis.call('DEL',KEYS[1]); "
                +"for i=3,#ARGV,2 do redis.call('HSET',KEYS[1],ARGV[i],ARGV[i+1]); end; redis.call('EXPIRE',KEYS[1],ARGV[2]); return 1",Long.class);
        var args=new java.util.ArrayList<String>();args.add(version);args.add(String.valueOf(remainingTtl(seconds)));
        values.forEach((k,v)->{args.add(k.toString());args.add(v.toString());});
        boolean success=Long.valueOf(1).equals(stringRedisTemplate.execute(script,List.of(key,versionKey(key)),args.toArray()));
        if(!success)diagnostics.count("version.reject",CacheDiagnostics.family(key),1);
        return success;
    }
    public void remainingRetry(String key,boolean abandon){diagnostics.count(abandon?"version.abandon":"version.reload",CacheDiagnostics.family(key),1);}
    /** Each retry uses fresh nonces, including repeated and late outbox attempts. */
    public void invalidateRemaining(List<String> keys){
        if(keys.isEmpty())return;
        var script=new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "local n=0; for i=1,#KEYS,2 do redis.call('SET',KEYS[i+1],ARGV[(i+1)/2+1],'EX',ARGV[1]); n=n+redis.call('DEL',KEYS[i]); end; return n",Long.class);
        var all=new java.util.ArrayList<String>();var args=new java.util.ArrayList<String>();args.add(String.valueOf(ttl.version()));
        for(String key:keys){all.add(key);all.add(versionKey(key));args.add(java.util.UUID.randomUUID().toString());}
        Long removed=stringRedisTemplate.execute(script,all,args.toArray());
        diagnostics.count("delete.calls",CacheDiagnostics.family(keys.getFirst()),1);
        diagnostics.count("delete.requested",CacheDiagnostics.family(keys.getFirst()),keys.size());
        diagnostics.count("delete.keys",CacheDiagnostics.family(keys.getFirst()),removed==null?0:removed);
    }

    /**
     * 分布式锁执行：调用方在 action 内自行实现「锁内双检」
     */
    public void executeWithLock(String lockKey, Runnable action) {
        RLock lock = redissonClient.getLock(lockKey);
        long before=System.nanoTime();lock.lock();
        diagnostics.lockAcquired(lockKey,System.nanoTime()-before);
        try {
            action.run();
        } finally {
            try {lock.unlock();}finally{diagnostics.lockReleased();}
        }
    }

    public Object hGet(String key, String field) {
        Object value=stringRedisTemplate.opsForHash().get(key, field);
        diagnostics.lookup(key,key+"/"+field,value==null?"miss":"hit");return value;
    }

    public List<Object> hMultiGet(String key, List<Object> fields) {
        List<Object> values=stringRedisTemplate.opsForHash().multiGet(key,fields);
        diagnostics.lookup(key,key+"/multi",allNonNull(values)?"hit":"miss");return values;
    }

    public Map<Object, Object> hGetAll(String key) {
        Map<Object,Object> values=stringRedisTemplate.opsForHash().entries(key);
        diagnostics.lookup(key,key,values.isEmpty()?"miss":values.containsKey(EMPTY_FIELD)?"negative":"hit");return values;
    }

    public void hPutAll(String key, Map<Object, Object> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForHash().putAll(key, map);
    }

    /**
     * 整体写入 Hash 并设置过期时间，避免需要自愈的缓存变成永久 Key。
     */
    public void hPutAllWithTtl(String key, Map<Object, Object> map, long timeout, TimeUnit unit) {
        if (map == null || map.isEmpty()) {
            return;
        }
        // Publish a complete snapshot and its TTL atomically; readers never observe partial fields.
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "redis.call('DEL',KEYS[1]); for i=2,#ARGV,2 do "
                + "redis.call('HSET',KEYS[1],ARGV[i],ARGV[i+1]); end; "
                + "redis.call('EXPIRE',KEYS[1],ARGV[1]); return 1", Long.class);
        var args = new java.util.ArrayList<String>();
        args.add(String.valueOf(Math.max(1,unit.toSeconds(timeout))));
        map.forEach((field,value) -> {args.add(field.toString());args.add(value.toString());});
        stringRedisTemplate.execute(script,List.of(key),args.toArray());
    }

    public void hPut(String key, String field, String value) {
        stringRedisTemplate.opsForHash().put(key, field, value);
    }

    /**
     * 删除整个 Key
     * <p>
     * 余票缓存是一个 hash（席别 -> 余票数），座位一旦发生占用/释放/售出，
     * 整个 hash 都不再可信，所以按 Key 整体删除让下次查询回源重算，而不是逐个字段修正。
     */
    public void delete(String key) {
        Boolean removed=stringRedisTemplate.delete(key);
        diagnostics.count("delete.calls",CacheDiagnostics.family(key),1);
        diagnostics.count("delete.keys",CacheDiagnostics.family(key),Boolean.TRUE.equals(removed)?1:0);
    }

    public void deleteAll(List<String> keys) {
        if (!keys.isEmpty()) {
            Long removed=stringRedisTemplate.delete(keys);
            diagnostics.count("delete.calls",CacheDiagnostics.family(keys.getFirst()),1);
            diagnostics.count("delete.requested",CacheDiagnostics.family(keys.getFirst()),keys.size());
            diagnostics.count("delete.keys",CacheDiagnostics.family(keys.getFirst()),removed==null?0:removed);
        }
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    /**
     * 判断集合中是否全部非空（hash multiGet 场景）
     */
    public static boolean allNonNull(List<Object> values) {
        return values != null && values.stream().allMatch(Objects::nonNull);
    }
}
