# 缓存专项复现说明

本轮只修改现有 Cache Aside。真实 MySQL 和 Redis 的地址、凭据沿用模块配置，通过环境变量传给测试进程，不写入报告。需要 Java 21、Maven、MySQL 客户端、Python 与 PyYAML。

从项目根目录执行：

```powershell
# 已保存带同样诊断的 A 组字节码；首次复现可恢复，不要用当前 B 组重新 freeze。
python 12306/my12306/services/ticket-services/tools/cache-ab.py restore
python 12306/my12306/services/ticket-services/tools/cache-ab.py prepare

# 诊断基线：20 并发短测、3 波击穿、确定性旧值回填、50 并发热点 HTTP。
python 12306/my12306/services/ticket-services/tools/cache-ab.py proof

# 正式七种场景：A/B 交替，50 并发，60 秒，三轮。
python 12306/my12306/services/ticket-services/tools/cache-ab.py matrix --scenarios hot-http,empty-city,missing-train,empty-search,unique-train,mixed,overlap-mixed

# 历史 S1 的 100 并发配置单独复测，不与 50 并发混算。
python 12306/my12306/services/ticket-services/tools/cache-ab.py matrix --concurrency 100 --scenarios hot-http

# 同时到达的 50 个请求，分别 30 波；多 Key 冷加载另行比较。
python 12306/my12306/services/ticket-services/tools/cache-ab.py matrix --rounds 1 --scenarios burst-city,burst-remaining,burst-cold,parallel-proof,expiry,pulses

# 优化后的真实 Redis、数据库正确性与故障检查。
python 12306/my12306/services/ticket-services/tools/cache-ab.py matrix --models B --rounds 1 --scenarios checks
python 12306/my12306/services/ticket-services/tools/summarize-cache-ab.py

# 仅删除脚本记录并严格校验名称的两套隔离数据库。
python 12306/my12306/services/ticket-services/tools/cache-ab.py cleanup
```

如果 baseline/classes 或隔离库已存在，restore/prepare 会拒绝覆盖；可直接从后续步骤开始。基线归档不包含 application.yml 或其他配置凭据，资源通过模块 src/main/resources 提供；本轮资源变化仅为 B 组新增 TTL 参数，A 组不读取这些参数。应保留首次源码摘要、带诊断基线摘要、候选摘要以及环境说明，不能用 Git HEAD 的旧 OD 实现替代 A。

单机压测与回归测试顺序运行，避免共享 CPU/MySQL 争用。清理只针对每次运行的测试前缀，Spring Redis 序列化与 Redisson NameMapper 均包含该前缀。源库只读取和复制；隔离库只选合法的 train 1 夹具，保留原有静态数据冲突报告，不对源库进行修复或迁移。

2026-10-10 测试中电脑重启过一次。重启前的短测、问题复现和已完成长测保留为诊断证据；正式数据从重启后重跑，并带 runId。current-run.json 和 evidence/run-*.json 逐场景记录完成情况。如再次中断，给原 matrix 命令追加 `--resume`，参数必须完全相同；不要把重启前后不同环境下的单轮合并为三轮结果。热点触发阈值时追加 `--start-round 4 --rounds 2 --scenarios hot-http`。

混合负载采用可复现的确定性循环 `(sequence + worker) % 10`：8 个查询、1 个单票占座、1 个真实取消，等价于固定的 80/10/10 请求构成，不使用随机生成器。每个线程先持有一张票以保证取消有对应归属；测量结束后剩余票统一取消，清理不计入 QPS。由于请求执行时间不同和测量结束边界，最终比例会有少量偏差，按实际成功数报告。失败请求独立列出，不用快速拒绝充当有效购票吞吐。

SQL 计数来自实际 MyBatis StatementHandler.prepare。夹具建立、数据对账和事件排空使用测试侧 JDBC，不计入业务 SQL QPS；不能把该指标称为整个 MySQL 服务器的全局 SQL QPS。父加载包含嵌套加载 SQL；总 SQL 应使用 cache.sql，不能将多个父子 load.sql 再次相加。

混合传播延迟用测试侧事件 Mapper 的 insert/delete 钩子，将持久化事件关联到原事务 orderSn，commit 返回时间与该事件成功执行 DEL/Lua 的返回时间比较。单次脉冲另行停止后续写入，逐个 OD 与数据库快照比较，连续三次一致才记收敛。脉冲 P99 包含数据库对账及三次读取的时间，是本机观测值，不是生产延迟承诺。

故障注入只作用于隔离测试客户端或失效调用：停用/恢复测试 Lettuce 客户端、让失效调用抛连接异常、让加载器抛数据库超时。不会停止共用的 Redis/MySQL 服务。它证明客户端异常与重试处理，不能替代 Redis 服务重启、网络分区、数据库真实超时等基础设施故障演练。

## 手动余票预热

`RemainingCacheWarmupTool` 是独立命令，不会在服务启动或周期任务中运行。参数为车次、OD JSON 文件、并发上限、每秒 OD 上限，默认后两者为 2、10。JSON 示例：

```json
[["北京南", "南京南"], ["北京南", "宁波"]]
```

使用构建得到的运行类路径执行该主类：

```text
edu.swu.fcj.my12306.biz.ticketservice.toolkit.RemainingCacheWarmupTool 1 ods.json 2 10
```

它复用正常余票加载器、OD 加载锁和版本校验；一次 OD 仍聚合所有席别。通过环境变量指定目标库和 Redis，实际执行前核对目标环境。工具不创建订单、不修改库存。预热时间不纳入查询吞吐统计，本轮没有新增生产周期性整车预热。

## 遗留无 TTL 缓存的切换

新加载器只在回源时原子发布 TTL，已有永久 Hash 命中不会自动得到 TTL。为避免在每次热命中增加一次 EXPIRE/TTL 请求，提供独立 `CacheTtlMigrationTool`：默认只读统计指定票务缓存前缀中 TTL=-1 的 Key；显式传入 `--apply` 才通过 Lua 条件设置 TTL，已有有限 TTL 不延长，锁和令牌桶不在修复范围内。切换时先停止旧版本写入这些缓存，再执行该工具或清理相应静态缓存以触发正常重建。本轮未执行业务 Redis 的修复，测试始终使用独立命名空间。


## HTTP 测量口径与补测

热点 HTTP 使用预热并复用的 Java HttpClient；正式采样前进行 10 秒相同并发的连接和应用预热。最终数据的 httpTimingScope=send-to-body：请求构造在计时前，计时终点为 client.send 返回、完整响应体已读取；JSON 语义校验在计时后。QPS 仍按完整成功循环数除以测量窗口，不排除校验对客户端吞吐的影响。此前 legacy-call 数据包含请求构造与响应校验，只保留为独立诊断组，不合并 P99。

```powershell
python 12306/my12306/services/ticket-services/tools/cache-ab.py matrix --rounds 5 --concurrency 50 --seconds 60 --http-warm-seconds 10 --scenarios hot-http
python 12306/my12306/services/ticket-services/tools/cache-ab.py matrix --rounds 3 --concurrency 100 --seconds 60 --http-warm-seconds 10 --scenarios hot-http
```

TTL 实验缩短 TTL 为 10 秒，100 个测试 Key 使用真实 Train 1 元数据，加载调用真实数据库 Mapper；仅用于比较分散效果，不模拟 100 个生产车次。parallel-proof 只在两次真实城市对 SQL 前各注入 200 ms 屏障，验证不相关加载的并行能力，不能当作自然负载性能收益。

所有测试结束后，可运行 `tools/cleanup-cache-test-redis.py` 只读列出遗留测试 Key，追加 `--apply` 删除。脚本从本轮日志提取严格校验过的测试前缀，只扫描这些命名空间。没有 FLUSHDB 或业务 Key 清理。
