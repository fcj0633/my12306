package edu.swu.fcj.my12306.biz.ticketservice.service.cache;

import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.InventoryCacheEventDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.InventoryCacheEventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class InventoryCacheEventProcessor {
    private final InventoryCacheEventMapper mapper;
    private final InventoryCacheInvalidator invalidator;
    private final MeterRegistry metrics;
    private final TransactionTemplate independentTransaction;
    private final java.util.concurrent.Executor executor;
    @Value("${my12306.inventory.cache-events.enabled:true}") private boolean enabled;

    public InventoryCacheEventProcessor(InventoryCacheEventMapper mapper, InventoryCacheInvalidator invalidator, MeterRegistry metrics,
            PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Qualifier("inventoryCacheExecutor") java.util.concurrent.Executor executor) {
        this.mapper=mapper; this.invalidator=invalidator; this.metrics=metrics;
        this.executor=executor;
        independentTransaction=new TransactionTemplate(transactionManager);
        independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void dispatch(InventoryCacheEventDO event) {
        // Do not open a second JDBC transaction while the committing thread still owns its connection.
        // Bounded wakeups keep Redis work out of the carriage critical section; overflow stays in the outbox.
        try { executor.execute(() -> process(event)); }
        catch (java.util.concurrent.RejectedExecutionException ex) {
            metrics.counter("my12306.inventory.cache-event.retry").increment();
        }
    }

    public void process(InventoryCacheEventDO event) {
        try {
            var segments=new java.util.TreeSet<Integer>();
            for (int s=event.getStartIndex();s<event.getEndIndex();s++) segments.add(s);
            invalidator.invalidate(event.getTrainId(),segments,Boolean.TRUE.equals(event.getInvalidateBucket()));
            // afterCommit still has the original connection bound: delete the event in a NEW transaction.
            independentTransaction.executeWithoutResult(status -> mapper.deleteById(event.getId()));
            metrics.counter("my12306.inventory.cache-event.success").increment();
        } catch (Exception ex) {
            metrics.counter("my12306.inventory.cache-event.retry").increment();
            log.warn("库存缓存失效未完成，保留事件等待重试。eventId={}",event.getId(),ex);
        }
    }

    @Scheduled(initialDelayString="${my12306.inventory.cache-events.retry-ms:5000}",
            fixedDelayString="${my12306.inventory.cache-events.retry-ms:5000}")
    public void retry() {
        if (!enabled) return;
        try { mapper.pending().forEach(this::process); }
        catch (Exception ex) { log.warn("库存缓存事件扫描失败",ex); }
    }
}
