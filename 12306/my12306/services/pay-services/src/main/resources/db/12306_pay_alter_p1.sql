-- P1 变更：新增本地消息表 t_pay_notify_message（支付成功后要发给下游的事件）
--
-- ① 背景：为什么需要这张表
--    P1 之前，支付成功后的下游通知是【同步串行 Feign】：PayServiceImpl.notifyPayResult 依次调
--    order 与 ticket。它有两个问题：
--      a) 事务边界被污染 —— notifyPayResult 从带 @Transactional 的 payCallback 调用，
--         Spring 事务沿调用栈传播，3 次远程调用全在事务里（P1 段1 已修：拆出 PayCallbackTxService）；
--      b) 强耦合且不能削峰 —— 支付服务必须知道"订单有个 pay-callback 接口、票务有个 pay-callback 接口"。
--
--    P1 段2 改为【链式事件驱动】：
--         Pay ──PAY_SUCCESS──> Order ──ORDER_PAID──> Ticket
--    支付服务只负责"把 PAY_SUCCESS 这件事发出去"，不再知道谁在消费。
--
-- ② 为什么用【本地消息表】而不是 RocketMQ 事务消息
--    事务消息的可靠性依赖 RocketMQ 的半消息 + 回查机制，换 MQ 就要重做；
--    本地消息表的做法是"把要发的消息与业务状态放进同一个本地事务"，普通消息即可达到同等可靠性。
--    代价是多一张表 + 一个扫描任务。
--
--    ⚠️ 关键认知：可靠性不来自消息中间件，而来自"本地落状态 + 补偿"。
--    中间件只保证尽力投递；消息会丢、会重复、会乱序，可靠性必须自己设计。
--
--    本项目其实早就有这张表的"简化版"：t_pay.notify_status 记录"这笔支付通知过下游没有"，
--    而 PayNotifyCompensateJob 就是它的补偿逻辑。本表只是把它显式化（把消息内容也落库）。
--
-- ③ 写入时机（顺序很重要）
--    与 t_pay 的状态推进【同一事务】：PayCallbackTxService.markPaid 里
--    "UPDATE t_pay 0→10" 与 "INSERT 本表 status=0" 一起提交。
--    这样"支付已入账"与"要通知下游"这两件事要么都成立、要么都不成立。
--    事务提交之后才由 PayNotifyMessageSender 真正发 MQ（低延迟），漏发的由扫描任务兜底（可靠性）。

USE 12306_pay;

-- 前置检查：表是否已存在。若返回 1 行，说明本脚本已执行过，不要重复执行。
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = '12306_pay'
  AND TABLE_NAME = 't_pay_notify_message';

CREATE TABLE IF NOT EXISTS `t_pay_notify_message`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `pay_sn`          varchar(64)  COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '支付流水号',
    `order_sn`        varchar(64)  COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单号',
    `event_type`      varchar(32)  COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '事件类型，如 PAY_SUCCESS',
    `payload`         varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '消息体（JSON，排查时可直接看原文）',
    `status`          int(3) DEFAULT 0 COMMENT '发送状态：0 待发送 10 已发送',
    `retry_count`     int(11) DEFAULT 0 COMMENT '已重试次数',
    `next_retry_time` datetime DEFAULT NULL COMMENT '下次可重试的时间（退避用）',
    `create_time`     datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    -- 幂等：同一笔支付的同一个事件只允许一条。重复回调即使并发插入，也会被这个唯一键挡掉。
    UNIQUE KEY `uk_pay_sn_event` (`pay_sn`, `event_type`) USING BTREE,
    -- 扫描任务按 (status, next_retry_time) 捞待发送的消息。
    KEY `idx_status_retry` (`status`, `next_retry_time`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '支付结果通知的本地消息表';

-- 验证：建完后应有唯一键 uk_pay_sn_event 与索引 idx_status_retry。
--
--   SHOW INDEX FROM 12306_pay.t_pay_notify_message;
