package edu.swu.fcj.my12306.biz.ticketservice.service.cache;

import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.InventoryCacheEventDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.InventoryCacheEventMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.SegmentRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transactional outbox: never lose cache invalidation if the process dies after reservation commit. */
@Service
@RequiredArgsConstructor
public class InventoryCacheEvents {
    private final InventoryCacheEventMapper mapper;
    private final InventoryCacheEventProcessor processor;

    public void enqueue(Long trainId, SegmentRange range, boolean invalidateBucket) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Inventory event must be part of the inventory transaction");
        }
        InventoryCacheEventDO event = new InventoryCacheEventDO();
        event.setTrainId(trainId);
        event.setStartIndex(range.start());
        event.setEndIndex(range.end());
        event.setInvalidateBucket(invalidateBucket);
        mapper.insert(event);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { processor.dispatch(event); }
        });
    }
}
