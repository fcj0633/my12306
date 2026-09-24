-- D4: an order number is the idempotency key supplied by ticket-service.
-- Run the duplicate query first. Stop the migration if it returns any row.
USE 12306_order;

SELECT `order_sn`, COUNT(*) AS duplicate_count
FROM `t_order`
WHERE `order_sn` IS NOT NULL
GROUP BY `order_sn`
HAVING COUNT(*) > 1;

ALTER TABLE `t_order`
    DROP INDEX `idx_order_sn`,
    ADD UNIQUE KEY `uk_order_sn` (`order_sn`) USING BTREE;
