# 重叠区间购票：基本区段库存实现与验证

本次将库存事实从独立的起终点（OD）记录切换为“物理座位 × 相邻站区段”。对外购票、订单、支付和取消接口不变；同一座位可以用于相邻或不重叠的旅程，重叠旅程不能同时占用。乘车人重复购票规则、独立乘车日期库存和已支付退票流程不在本次范围。

## 已实现的行为

- `t_inventory_station` 保存迁移时校验过的数值站序投影。序号重复、缺失、非整数、重复站名、首末站不匹配都会阻止迁移；运行时所有库存路径使用同一投影。
- `t_physical_seat` 保存物理坐标，唯一约束是车次、车厢、座号；同一坐标不能配置多个席别。`t_seat_segment` 的主键是物理座位 ID、区段序号。
- 选座与余票统计检查请求覆盖的每个基本区段是否存在且可用。缺行不能视为可售；各段余票的最小值不能代表直达余票。
- 购票在本地事务中生成车票，按席别批量条件更新区段，写入 `ticket_id/order_sn` 归属。影响行数必须等于座位数乘区段数，不足就整体回滚。保留车厢轮转、单车厢优先、跨车厢兜底及现有分布式锁键。
- 回调先按订单锁定并认领未支付车票，再转换该车票拥有的全部区段。支付转换为 SOLD；取消转换为 AVAILABLE 并清空归属。部分转换、归属不符会回滚。重复及迟到回调不能改变新订单占用。
- 远程建单保持在本地事务及车厢锁之外。提交结果未知不按已回滚处理，仍交给现有订单核对和孤儿恢复任务。

旧 `t_seat` 表保留用于迁移核对和离线重建，在线库存读写已经不再依赖其可售状态。`SeatDO` 继续用于三列选座结果投影，此时返回的 ID 是物理座位 ID；其继承的旧表 CRUD 仅供历史工具使用，不能用于新区段占用。

## 缓存与准入

余票按请求 OD 一次聚合各席别，默认缓存 30 秒，取消原有整车全部 OD、全部席别逐次 COUNT 的加载方式。占座及实际释放在同一数据库事务写入 `t_inventory_cache_event`，提交后立即交给两线程、有界队列的处理器，批量删除重叠 OD 缓存；失败或队列满时保留数据库事件，每 5 秒重试。支付不改变可售容量，因此不删除余票。

后台处理避免在提交线程仍持有数据库连接时同步打开第二个事务，减少车厢锁持有时间和连接池饥饿风险。事件在缓存操作成功后才用独立事务删除；重复处理仅重复删除缓存，不会增加库存。缓存仍为最终一致性，提交到失效之间及并发回源期间允许短暂旧值；数据库条件更新负责防超卖，TTL 限制旧值存活时间。

令牌桶的新版 key 为 `my12306-ticket-service:train_station_token_bucket:segments:v1:<trainId>`，Hash 字段是 `<segmentIndex>_<seatType>`。Lua 先检查全部区段、席别，再统一扣减。装载用一次基本区段分组聚合，补齐零库存席别并原子发布整个 Hash 和 10 分钟 TTL。

区段容量只决定准入，不能证明存在贯穿全程的同一座位。Redis 异常保留降级放行；数据库选座可以在碎片化库存下拒绝已经准入的请求。取消提交后的事件或本地明确失败使整车桶失效，下一次重新装载。内部 `returnToken` 方法保留调用兼容性，但现在只使桶失效，不做加法；旧归还 Lua 已移除。失效失败时保留偏差直到重试或 TTL 重建，不能把令牌数作为库存事实。

配置项：

| 配置 | 默认值 | 用途 |
|---|---:|---|
| `my12306.inventory.remaining-ttl-seconds` | 30 | OD 余票缓存有效期 |
| `my12306.inventory.cache-events.enabled` | true | 后台失效事件扫描；提交后的队列处理仍然启用 |
| `my12306.inventory.cache-events.retry-ms` | 5000 | 失效事件重试周期 |
| `my12306.availability.token-bucket.expire-minutes` | 10 | 区段令牌桶有效期 |

新增运行指标为 `my12306.inventory.allocate`、`my12306.inventory.allocate.shortage`、`my12306.inventory.segment.update`、`my12306.inventory.segment.updated-rows`、`my12306.inventory.remaining.load`、`my12306.inventory.cache-event.success/retry`、`my12306.token.lua`。选座不足及更新行数是事务尝试指标，可能包含重试和随后回滚的操作，不能用于已售票对账。结合现有车厢锁等待、持有时间和数据库重试指标观察完整链路。

## 迁移、切换与回滚

应用不会自动迁移。新版本启动时检查区段库存表是否存在、库存是否非空以及区段是否完整；未迁移或部分迁移会阻止启动。

工具位置为 `12306/my12306/services/ticket-services/tools/segment-inventory.py`，读取当前票务配置，凭据只通过环境传给 Java。可使用 `TICKET_DB_URL/TICKET_DB_USER/TICKET_DB_PASSWORD` 指定需要检查的数据库。Java 工具还可独立运行，不启动 Spring 服务、Nacos 或 MQ。

从仓库根目录执行只读检查：

```powershell
python 12306/my12306/services/ticket-services/tools/segment-inventory.py check --report docs/11-segment-inventory/evidence/source-check.json
```

正式切换步骤：

1. 停止所有旧票务实例的购票、支付回调、取消回调及孤儿恢复写入；保留延迟消息和通知，让新实例启动后继续处理。备份旧库存和车票数据。
2. 执行 `check`，逐项核对冲突。不能通过忽略异常车次、覆盖归属或删除有效车票使正式迁移强行通过。
3. 执行以下离线操作。`--writers-stopped` 表示操作者已停写，工具不会替操作者停止服务。

```powershell
python 12306/my12306/services/ticket-services/tools/segment-inventory.py migrate --writers-stopped
python 12306/my12306/services/ticket-services/tools/segment-inventory.py verify --writers-stopped
python 12306/my12306/services/ticket-services/tools/segment-inventory.py clear-caches --writers-stopped
```

4. 在全部票务实例统一切换后启动新版本，恢复流量。不可同时运行 OD 库存写入版本与区段库存写入版本。

`migrate` 先创建新表（MySQL DDL 单独提交），再校验源数据并在一个数据事务中建立座位、区段及有效车票归属；数据事务失败时整体回滚，新表结构保留。目标表非空时拒绝再次填充，使用 `verify` 对账。`verify` 检查物理坐标、站序、区段完整性及所有有效车票的具体归属。缓存清理仅扫描并删除两个库存缓存前缀，不清理登录、用户、订单或延迟队列。

回滚也必须停写：执行 `rebuild-legacy --writers-stopped`，根据有效车票对所有旧 OD 重算占用，再清库存缓存、切换程序。新模型已经产生交易后，不能仅还原旧程序或旧数据库备份。旧程序仍存在原先的跨 OD 正确性缺陷，回滚只用于受控维护，不能视为已具备新区段模型的防超卖能力。

### 当前数据阻止正式切换

2026-10-10 对实际配置票务库进行了只读 `check`，报告见 [source-check.json](evidence/source-check.json)。去重后有 501 项冲突：车次 3 有 500 个物理坐标存在席别冲突；车次 4 的首末站与站点列表不匹配。迁移按要求阻止切换，业务库没有执行建表、库存重置、车票修改或迁移。

## 验证与性能报告

可复现的验证命令：

```powershell
python 12306/my12306/services/ticket-services/tools/validate-segment-inventory.py
python 12306/my12306/services/ticket-services/tools/validate-segment-inventory.py --benchmark
```

验证器新建带严格前缀的独立 MySQL 数据库，业务库仅作为只读样例来源。测试库选择有效车次 1，清理复制的车票和可售状态后迁移；测试完仅删除本次创建的数据库。真实 Redis 测试仅使用随机的 `codex:segment-lua-test:*` key，并在测试后删除。需要 Java 21、Maven、MySQL 客户端和带 PyYAML 的 Python。

正确性覆盖重叠并发（绕过分布式锁）、相邻复用、取消一段保留另一段、库存碎片化、缺失区段、部分占座和回调回滚、多乘客和混席别、重复及迟到回调、支付取消竞争、Redis Lua 原子扣减、缓存事件重试，以及历史冲突、迁移回滚和旧模型重建。每轮新生成的测试报告汇总在 `target/segment-validation/run.json`，排除旧报告残留。

SQL 性能实验对两种库存模型使用同一状态，覆盖 1/2/4 个区段、1/5 人、20/50/100 并发，并以三轮交替顺序测量。固定共用 20 个数据库连接；查询记录吞吐、平均及 P95/P99，写入记录“选中座位校验＋条件库存更新＋提交”的耗时和更新行数。它不包含车票、失效事件、Redis、车厢锁、HTTP 或远程订单服务，不代表完整购票 QPS。详情及实际结果见 [性能报告](PERFORMANCE.md)。

补充的 **50 并发、每笔 1 张票完整占座主事务**测试直接调用 Spring 事务代理，覆盖真实选座、车票写入、库存占用、缓存事件写入和提交。旧事务、旧选座器和旧 Mapper 的测试基线取自改造前 HEAD `164e13b`；每种区段长度交替运行三轮，每轮每版 10,000 笔，共 180,000 笔计时事务。两版测试连接池统一为 20，异步缓存处理替换为空实现，车厢锁等待和远程调用不计入。测量范围、人工车厢布局及实测结果见 [完整主事务性能报告](PURCHASE-TRANSACTION-PERFORMANCE.md)。
