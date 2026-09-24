-- 支付库（单库单表，不分片；与参考项目的 12306_pay_0..N 分片版本刻意不同）
CREATE DATABASE IF NOT EXISTS 12306_pay DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE 12306_pay;

CREATE TABLE IF NOT EXISTS `t_pay`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `pay_sn`         varchar(64)  COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '支付流水号',
    `order_sn`       varchar(64)  COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `channel`        varchar(64)  COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '支付渠道',
    `trade_type`     varchar(64)  COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '支付环境/交易类型',
    `subject`        varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单标题',
    `total_amount`   int(11) DEFAULT NULL COMMENT '应付金额（分）',
    `pay_amount`     int(11) DEFAULT NULL COMMENT '实付金额（分）',
    `trade_no`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '三方交易凭证号',
    `gmt_payment`    datetime     DEFAULT NULL COMMENT '付款时间',
    `status`         int(3) DEFAULT NULL COMMENT '支付状态：0 待支付 10 支付成功 30 交易关闭',
    `notify_status`  int(3) DEFAULT 0 COMMENT '下游通知状态：0 未完成 1 已完成',
    `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime     DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`),
    KEY              `idx_pay_sn` (`pay_sn`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付单表';
