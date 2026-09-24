-- P5 变更：给 t_seat 加选座查询的复合索引
--
-- 背景：选座查询在购票链路的【席别锁临界区之内】执行（SeatAllocator.java:26-33，
-- 由 PurchaseTicketServiceImpl.reserveLocally 调用）。SQL 形态是 5 个等值条件 + 2 列排序：
--
--   SELECT * FROM t_seat
--    WHERE train_id = ? AND seat_type = ? AND seat_status = 0
--      AND start_station = ? AND end_station = ?        -- MyBatis-Plus 另追加 del_flag = 0
--    ORDER BY carriage_number, seat_number;
--
-- 问题：t_seat 上只有 PRIMARY(id) 与 idx_train_id(train_id)。实测执行计划为
--   type=ref / key=idx_train_id / key_len=9 / rows=13869 / Extra: Using where; Using filesort
-- 即先按 train_id 定位到 9,600 行，再【逐行回表】读出完整行才能判断 seat_type / seat_status /
-- 站名，筛出 809 行之后才排序。这条查询每次请求都执行，而它就压在席别锁里。
--
-- 改动：把 5 个等值条件按"等值列优先"的顺序建成复合索引，使 B+Tree 一次定位到连续的
-- 809 个条目，回表次数由 9,600 降到 809（−91.6%）。
--
-- 实测量化（2026-09-24 同会话 A/B：DROP INDEX / ADD INDEX 背靠背，
-- 每态 5 次 EXPLAIN ANALYZE 取中位数，预热 1 次不计入，同一台机器同一份数据）：
--
--   | 指标                | A 无 idx_seat_query | B 有 idx_seat_query | 变化    |
--   | ------------------- | ------------------- | ------------------- | ------- |
--   | 实际使用的索引      | idx_train_id        | idx_seat_query      | —       |
--   | 回表行数            | 9,600               | 809                 | −91.6%  |
--   | 索引查找+回表       | 24.0 ms             | 2.62 ms             | −89.1%  |
--   | 查询总耗时          | 35.6 ms             | 11.1 ms             | −68.8%  |
--
-- 注意口径：以上是【同会话相对对比】。D4 的历史文档记录同一条查询总耗时 72.2ms，
-- 与本次 A 组的 35.6ms 相差一倍——那正是跨会话不可比的例证（当时有 Maven 打包负载）。
-- 引用数字时必须带口径，不要拿 72.2ms 和 11.1ms 直接相减。
--
-- 列序依据（5 列全是等值条件，列序不影响"能否用上"，只影响中间结果集收敛速度）：
--   train_id       第 1 位 —— 必经条件，且与已有 idx_train_id 前缀一致
--   seat_type      第 2 位 —— 把 9,600 收敛到 8,100
--   seat_status    第 3 位 —— 购票场景下选择性好，故排在站名之前
--   start_station  第 4 位 —— 最后的精确过滤
--   end_station    第 5 位
--
-- 两个已知取舍（复核时不要"顺手"改掉）：
--   ① filesort 不会消失。ORDER BY 的 carriage_number / seat_number 不在本索引里，仍要对 809 行
--      排序 —— 这也是加索引后剩余 11.1ms 里的大头（总耗时 11.1ms − 索引查找与回表 2.62ms ≈ 8.5ms）。
--      消掉它必须扩成 7 列，代价是索引条目 50→70 字节，并且让 seat_status 的每次更新多维护两列。
--      实测总行数仅 27,480、数据量有限，本项目选择"先只加 5 列等值索引"。
--      （依据：docs/索引设计学习文档.md:879-901）
--   ② 写路径有成本。seat_status 是索引的第 3 列，而每次占座 / 回滚 / 售出都要 UPDATE 它，
--      因此每一个座位锁都会多一次索引维护。但这条查询每次请求都执行，收益大于成本。
--
-- 未包含 del_flag：它基数极低（几乎全为 0），放进索引换不来额外过滤能力，
-- 而这条查询是 SELECT *，反正要回表取其余列。

USE 12306_ticket;

-- 前置检查 1：索引是否已存在。若返回任何行，说明本脚本已执行过，不要重复 ADD。
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = '12306_ticket'
  AND TABLE_NAME = 't_seat'
  AND INDEX_NAME = 'idx_seat_query';

-- 前置检查 2：行格式必须是 Dynamic（或 Compressed）。
-- start_station / end_station 都是 varchar(256) utf8mb4，本索引的【声明】key 长度为
-- 9 + 5 + 5 + 1025 + 1025 = 2,069 字节，只有 3,072 字节上限才容得下；
-- 若是 Compact / Redundant（上限 767 字节），下面的 ALTER 会直接报错 1071。
SELECT TABLE_NAME, ROW_FORMAT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = '12306_ticket'
  AND TABLE_NAME = 't_seat';

ALTER TABLE `t_seat`
    ADD KEY `idx_seat_query` (`train_id`, `seat_type`, `seat_status`, `start_station`, `end_station`) USING BTREE;

-- 验证：跑下面的 EXPLAIN，期望 type=ref、rows 从 13,869 大幅下降到约 809。
-- 【注意】Extra 里仍然会有 Using filesort —— 这是取舍 ① 的预期结果，不是本次改动失败。
--
--   EXPLAIN SELECT * FROM t_seat
--    WHERE train_id = 1 AND seat_type = 2 AND seat_status = 0
--      AND start_station = '北京南' AND end_station = '宁波' AND del_flag = 0
--    ORDER BY carriage_number, seat_number;
