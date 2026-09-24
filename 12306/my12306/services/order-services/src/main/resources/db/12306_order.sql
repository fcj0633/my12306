CREATE DATABASE IF NOT EXISTS 12306_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE 12306_order;

CREATE TABLE IF NOT EXISTS `t_order`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`       varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`        bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`       bigint(20) DEFAULT NULL COMMENT '列车ID',
    `train_number`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列车车次',
    `riding_date`    date                                    DEFAULT NULL COMMENT '乘车日期',
    `departure`      varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '出发站点',
    `arrival`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '到达站点',
    `departure_time` datetime                                DEFAULT NULL COMMENT '出发时间',
    `arrival_time`   datetime                                DEFAULT NULL COMMENT '到达时间',
    `source`         int(3) DEFAULT NULL COMMENT '订单来源：0 互联网 1 线下',
    `status`         int(3) DEFAULT NULL COMMENT '订单状态：0 待支付 10 已支付 30 已取消',
    `order_time`     datetime                                DEFAULT NULL COMMENT '下单时间',
    `pay_type`       int(3) DEFAULT NULL COMMENT '支付方式',
    `pay_time`       datetime                                DEFAULT NULL COMMENT '支付时间',
    `create_time`    datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`    datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`       tinyint(1) DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY              `idx_user_id` (`user_id`) USING BTREE,
    UNIQUE KEY       `uk_order_sn` (`order_sn`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

CREATE TABLE IF NOT EXISTS `t_order_item`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `order_sn`        varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '订单号',
    `user_id`         bigint(20) DEFAULT NULL COMMENT '用户ID',
    `username`        varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
    `train_id`        bigint(20) DEFAULT NULL COMMENT '列车ID',
    `carriage_number` varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '车厢号',
    `seat_type`       int(3) DEFAULT NULL COMMENT '座位类型',
    `seat_number`     varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '座位号',
    `real_name`       varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
    `id_type`         int(3) DEFAULT NULL COMMENT '证件类型',
    `id_card`         varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '证件号',
    `ticket_type`     int(3) DEFAULT NULL COMMENT '车票类型',
    `phone`           varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
    `status`          int(3) DEFAULT NULL COMMENT '订单明细状态：0 待支付 10 已支付 30 已取消',
    `amount`          int(11) DEFAULT NULL COMMENT '订单金额（分）',
    `create_time`     datetime                                DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime                                DEFAULT NULL COMMENT '修改时间',
    `del_flag`        tinyint(1) DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (`id`),
    KEY               `idx_order_sn` (`order_sn`) USING BTREE,
    KEY               `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';
