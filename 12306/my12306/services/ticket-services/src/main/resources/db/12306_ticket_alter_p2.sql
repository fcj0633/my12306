-- P2 变更：车票表补齐"区间 + 席别"坐标
--
-- 背景：P1 写入 t_ticket 时只记录了车次、车厢、座号、乘车人。
-- 但 P2 的支付成功回调与取消回滚回调，需要按
-- 「车次 + 出发站 + 到达站 + 席别 + 车厢号 + 座号」精确定位 t_seat 中那一行。
-- 同一节车厢的同一个座号，在不同发售区间上对应的是不同的座位记录，
-- 少任何一个维度都可能改到别的区间，从而把别人的票错误地改成可售（超卖）。
--
-- 因此给车票记录补上定位座位所需的三个坐标列。

USE 12306_ticket;

ALTER TABLE `t_ticket`
    ADD COLUMN `seat_type`     int(3) DEFAULT NULL COMMENT '座位类型' AFTER `seat_number`,
    ADD COLUMN `start_station` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '出发站（发售区间起点）' AFTER `seat_type`,
    ADD COLUMN `end_station`   varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '到达站（发售区间终点）' AFTER `start_station`;
