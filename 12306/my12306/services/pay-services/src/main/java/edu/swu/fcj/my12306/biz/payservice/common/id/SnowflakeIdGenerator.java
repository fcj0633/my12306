package edu.swu.fcj.my12306.biz.payservice.common.id;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * P2-5：把雪花 ID 的 workerId / datacenterId 从"隐式推导"改成"显式配置"。
 * <p>
 * 之前 paySn / tradeNo 用的是 Hutool 的进程级默认雪花单例（IdUtil 的无参快捷方法）。
 * 实测（反编译 + 双 JVM 取证，见 docs/5-后续开发规划/p2/results/p2-5-加锁前取证.md）：
 * <ul>
 *   <li>{@code IdConstants.DEFAULT_WORKER_ID = hash(String(datacenterId) + PID) & 0xffff % 32}
 *       —— 混入了 PID，但只剩 32 个槽位；</li>
 *   <li>{@code DEFAULT_DATACENTER_ID} 由网卡 MAC 推出 ⇒ <b>同一台机器上的多个实例必然相同</b>；</li>
 *   <li>于是"两个实例恰好同号"是约 1/32 的概率事件；一旦同号且真正并发，
 *       实测重复率约 40%（20 万 ID 里重复 79,624 个）。</li>
 * </ul>
 * 显式分配把"3% 概率踩到 40% 重复"变成"确定性不撞号"，且不再依赖任何外部组件。
 * <p>
 * 注意这里【不】做 workerId 的自动回收/租约分配：本项目实例数固定且少，人工分配成本≈0，
 * 启动日志可直接核对。方案对比见 P2 文档 26.5 / 28.2。
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    private static final long MAX_WORKER_ID = 31L;

    private static final long MAX_DATACENTER_ID = 31L;

    /** 单例复用：每次 new 会重置 sequence，反而更容易在同一毫秒撞号。 */
    private final Snowflake snowflake;

    public SnowflakeIdGenerator(@Value("${my12306.snowflake.worker-id}") long workerId,
                                @Value("${my12306.snowflake.datacenter-id}") long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("my12306.snowflake.worker-id 必须在 0..31 之间，实际=" + workerId);
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("my12306.snowflake.datacenter-id 必须在 0..31 之间，实际=" + datacenterId);
        }
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
        // 这行日志是 P2 的验收依据之一：两个实例的 workerId 必须不同。
        log.info("Snowflake 初始化完成，workerId={}，datacenterId={}", workerId, datacenterId);
    }

    public String nextId() {
        return snowflake.nextIdStr();
    }
}
