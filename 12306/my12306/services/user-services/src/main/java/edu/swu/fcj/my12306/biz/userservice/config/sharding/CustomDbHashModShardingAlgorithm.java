package edu.swu.fcj.my12306.biz.userservice.config.sharding;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.Properties;

/**
 * 库分片算法（对齐原项目）：suffix = |hash| % shardingCount / tableShardingCount
 * <p>
 * 以 sharding-count=32、table-sharding-count=16 为例：
 * |hash| % 32 落在 [0,16) → ds_0；落在 [16,32) → ds_1。
 * 配合表算法 HASH_MOD 32，实现"先分库再分表"。
 */
public final class CustomDbHashModShardingAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {

    private static final String SHARDING_COUNT_KEY = "sharding-count";
    private static final String TABLE_SHARDING_COUNT_KEY = "table-sharding-count";

    private int shardingCount;
    private int tableShardingCount;

    @Override
    public void init(Properties props) {
        shardingCount = Integer.parseInt(props.getProperty("sharding-count"));
        tableShardingCount = Integer.parseInt(props.getProperty("table-sharding-count"));
    }

    @Override
    public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<Comparable<?>> shardingValue) {
        int suffix = (int) (hashShardingValue(shardingValue.getValue()) % shardingCount / tableShardingCount);
        return availableTargetNames.stream()
                .filter(target -> target.endsWith(String.valueOf(suffix)))
                .findFirst()
                .orElse(null);
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames, RangeShardingValue<Comparable<?>> shardingValue) {
        return availableTargetNames;
    }

    private long hashShardingValue(Object shardingValue) {
        return Math.abs((long) shardingValue.hashCode());
    }

    @Override
    public String getType() {
        return "CLASS_BASED";
    }
}
