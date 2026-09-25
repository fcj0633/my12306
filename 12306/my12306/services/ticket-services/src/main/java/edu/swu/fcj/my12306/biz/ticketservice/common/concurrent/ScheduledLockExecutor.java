package edu.swu.fcj.my12306.biz.ticketservice.common.concurrent;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * P2-4：定时任务的"集群内每轮只执行一次"。
 * <p>
 * 要解决的不是【业务幂等】而是【重复执行】—— 这两件事必须分开看：
 * <ul>
 *   <li>业务幂等（条件更新 + 影响行数）保证"重复执行不会把状态弄错"，正确性不依赖这把锁；</li>
 *   <li>本类保证"同一轮不要有两个实例各做一遍"，省掉重复的远程调用、重复的扫描，
 *       以及"累加型指标被打了两遍"这类观测失真。</li>
 * </ul>
 * 所以拿不到锁时【跳过是正常的】，不能记成失败或告警。
 *
 * <h3>为什么是"带租约的 leader"而不是"任务执行期间互斥"</h3>
 * 第一版实现用的是无参 {@code lock()} + {@code finally} 里 {@code unlock()}：
 * 锁只覆盖任务执行的那几毫秒。实测（ticket 双实例，3 轮）结果为【两侧每轮各执行一次、跳过 0 次】——
 * 因为两个实例的调度起点相差约 3 秒，而任务只需几毫秒，后到的那一侧发现锁已空闲，于是又执行一遍。
 * 也就是说那种写法只提供"不并发执行"，而本项目真正要的是"每轮只有一个实例执行"。
 * <p>
 * 改成本实现后：{@code tryLock(0, leaseMillis, MILLISECONDS)} 且【执行完不解锁】，
 * 锁一直持有到租约到期。本轮内另一实例到达时锁仍被持有 ⇒ 跳过；下一轮租约到期后重新竞争。
 * 持有者进程崩溃时也由租约自然过期把领导权交出去，不会永久锁死。
 * <p>
 * <b>前提条件</b>：{@code leaseMillis} 必须【大于任务的最坏执行时长】。否则任务还没跑完锁就过期，
 * 又退化成并发执行。这里统一传该任务自己的调度间隔，既天然覆盖整轮，又远大于毫秒级的扫描耗时。
 * 这也是为什么【不能】给一个比任务耗时还短的租期 —— 与"锁提前失效导致重复执行"是同一个坑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledLockExecutor {

    private final RedissonClient redissonClient;

    private final MeterRegistry meterRegistry;

    /** 实例标识，仅用于日志取证：多实例下要能分辨是哪台机器认领/跳过了本轮任务。 */
    @Value("${server.port:0}")
    private int instancePort;

    /**
     * 尝试认领本轮任务。拿不到立即返回，不等待。
     *
     * @param lockKey     全集群同一把锁的 key，不能含实例标识
     * @param leaseMillis 租约时长，应等于（或略大于）该任务的调度间隔
     * @return true 表示本实例认领并执行了本轮；false 表示本轮已由其它实例认领
     */
    public boolean runWithLock(String lockKey, long leaseMillis, Runnable task) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0L, leaseMillis, TimeUnit.MILLISECONDS)) {
                meterRegistry.counter("my12306.job.skipped", "job", lockKey).increment();
                log.info("[instance={}] 定时任务跳过（本轮已由其它实例认领）job={}", instancePort, lockKey);
                return false;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("[instance={}] 定时任务等待锁时被中断 job={}", instancePort, lockKey);
            return false;
        }

        // 刻意不放在 finally 里解锁：锁要一直持有到租约到期，本轮内其它实例才不会补跑。
        try {
            task.run();
            meterRegistry.counter("my12306.job.executed", "job", lockKey).increment();
            log.info("[instance={}] 定时任务执行完成 job={}", instancePort, lockKey);
        } catch (Throwable ex) {
            // 本轮已被本实例认领，就不该让别的实例在同一轮补跑；失败只计数并记录，等下一轮。
            meterRegistry.counter("my12306.job.failed", "job", lockKey).increment();
            log.error("[instance={}] 定时任务执行失败 job={}（本轮已认领，不再由其它实例补跑）",
                    instancePort, lockKey, ex);
        }
        return true;
    }
}
