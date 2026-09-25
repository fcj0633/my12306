这份文档已经足够把 P0/P1 当成两个完整的项目优化故事来讲了。最重要的是：**P0 是性能定位与索引优化，P1 是事务边界重构 + MQ 异步化 + 可靠消息设计。** 文档也明确以当前源码和实测为准，而不是照原计划复述。

# 一、先看整体：P0 和 P1 分别做了什么

P0 没有改业务架构，它解决的是购票主链路里“锁持有时间太长”的问题；P1 才真正改变了支付成功后的服务协作方式。文档里的最终定位很清楚：P0 是“关 SQL 同步日志 + 座位查询加联合索引”，P1 段1是“把支付本地事务从远程调用里拆出来”，P1 段2则是“Feign 同步通知改成 RocketMQ 链式事件”。

可以先把最终演进记成：

```text
P0
购票链路太慢
↓
先定位：不是只有 MySQL 慢
↓
关 StdOutImpl
+
给选座 SQL 增加联合索引
↓
减少锁内耗时


P1
支付成功后同步 Feign
↓
先把本地事务边界缩短
↓
再改成 MQ
↓
本地消息表保证生产端可靠性
↓
Order 消费 PAY_SUCCESS
↓
Order 发 ORDER_PAID
↓
Ticket 消费并出票
```

---

# 二、P0：它真正解决的是“锁内做了太多无效工作”

你前面已经学过席别锁：

```text
seat-type lock
↓
选座
↓
占座
↓
写 ticket
↓
commit
↓
释放锁
```

这种锁的问题在于：

> 锁里面任何一条 SQL 多花 20ms，后面的并发请求都要跟着等。

所以 P0 的重点不是单纯：

> “优化了一条 SQL。”

而是：

> **减少高并发临界区内部的执行成本。**

文档记录，P0 前选座查询只有 `idx_train_id(train_id)`，但 SQL 实际还需要按席别、座位状态、起终站筛选并排序，因此先通过索引拿到约 9600 行，再筛到约 809 行。与此同时，MyBatis `StdOutImpl` 还在同步输出 SQL 日志。

---

# 三、P0 第一项改动：关掉 `StdOutImpl`

这其实是一个很好的性能排查故事。

一开始看到：

```text
锁持有 300ms+
```

很容易第一反应：

```text
MySQL 慢
索引慢
事务慢
```

但实测发现：

```text
纯 MySQL 5 条 SQL
≈ 45.7ms
```

而关闭 SQL 同步日志之后：

```text
锁持有中位数
350.2ms → 182.4ms
```

下降接近一半。

这里真正值得学习的是：

> **不要看到“数据库链路慢”就默认瓶颈一定在数据库。**

`StdOutImpl` 是同步输出：

```text
业务线程
↓
执行 SQL
↓
格式化 SQL
↓
写控制台
↓
才能继续
```

在高并发环境里控制台 IO 本身就可能非常慢。

所以这个故事面试时比“我把日志关了”更好的讲法是：

> 我把锁持有时间拆开测量，发现纯数据库时间只占一小部分，因此继续排查应用侧，最后定位到 MyBatis 同步 SQL 日志。关闭后临界区耗时明显下降，这让我意识到优化前先建立可验证的性能分解非常重要。

---

# 四、P0 第二项改动：`idx_seat_query`

原来只有：

```sql
INDEX idx_train_id(train_id)
```

但查询大致是：

```sql
WHERE train_id = ?
  AND seat_type = ?
  AND seat_status = ?
  AND start_station = ?
  AND end_station = ?
ORDER BY carriage_number, seat_number
```

所以原执行过程类似：

```text
train_id 索引
↓
9600 行
↓
WHERE 再过滤
↓
809 行
↓
排序
```

新的索引：

```sql
(train_id,
 seat_type,
 seat_status,
 start_station,
 end_station)
```

目的就是让：

```text
WHERE 过滤
```

尽量提前到 B+Tree 定位阶段完成。

最终实测，同会话 A/B 中：

```text
实际回表/候选：
9600 → 809

索引查找 + 回表：
24ms → 2.62ms

查询：
35.6ms → 11.1ms
```

文档记录的下降分别约为 91.6%、89.1% 和 68.8%。

这里最值得注意的是：

> **没有为了消除 `filesort` 强行做 7 列超宽索引。**

排序依然存在，因为：

```text
carriage_number
seat_number
```

不在联合索引里。

这个取舍是合理的：

> 先解决 9600→809 的扫描浪费，排序 809 行是不是值得继续优化，要继续用数据判断。

---

# 五、所以 P0 的核心不是“会建联合索引”

P0 真正可讲的是完整闭环：

```text
发现：
锁持有时间异常

↓

拆分：
纯 MySQL vs 应用侧

↓

发现：
日志成本高
+
座位查询扫描量高

↓

分别优化：
NoLoggingImpl
+
idx_seat_query

↓

EXPLAIN / 实测验证
```

所以它连接的是：

- Redisson 锁
- MySQL B+Tree
- 联合索引
- EXPLAIN ANALYZE
- 临界区
- 性能测试方法

这是一个非常完整的面试故事。

---

# 六、进入 P1：先看原支付链路

P1 之前：

```text
Pay.payCallback
@Transactional

↓
更新 t_pay：
WAIT_PAY → PAID

↓
notifyPayResult

↓ Feign
查 Order 详情

↓ Feign
Order → PAID

↓ Feign
Ticket/Seat → PAID/SOLD

↓
notify_status → 1

↓
事务提交
```

文档明确说明，这 3 次远程调用都发生在支付事务中。

但这里一定注意一个已经纠正的重要点：

> **远程调用失败不会把 Pay 的 PAID 回滚。**

因为 `notifyPayResult()` 最后：

```java
catch (Throwable ex) {
    log.error(...);
    return false;
}
```

异常被吞掉了。

所以 P1 段1解决的不是：

```text
“钱扣了但数据库被事务回滚”
```

而是：

```text
事务被远程 IO 拉得太长
```

文档对此专门进行了纠错。

---

# 七、P1 段1：为什么要新建 `PayCallbackTxService`

这是 P1 第一处很值得理解的设计。

原来：

```java
@Transactional
payCallback() {
    updatePay();
    feign1();
    feign2();
    feign3();
}
```

现在变成：

```text
PayServiceImpl.payCallback()
没有事务

↓
PayCallbackTxService.markPaid()
@Transactional

    update t_pay
    INSERT 本地消息（mq 模式）
    COMMIT

↓ 方法返回

↓
再发送 MQ / 调 Feign
```

当前最终实现不是 `afterCommit`，而是：

> **独立事务 Bean。**

文档里的当前链路非常明确：`PayServiceImpl.payCallback()` 本身无事务，只有 `PayCallbackTxService.markPaid()` 有事务；事务方法返回后才进行 MQ 发送或 Feign 通知。

---

# 八、为什么独立 Bean 比 `afterCommit` 更符合这个项目

这个细节很值得面试讲。

原计划考虑：

```java
afterCommit(() -> notify())
```

但最终没有采用。

原因是 Spring 提交过程大致：

```text
doCommit
↓
afterCommit
↓
afterCompletion
↓
cleanupAfterCompletion
```

`afterCommit` 时：

```text
数据库已经 commit
行锁已经释放
```

但：

```text
连接还没完全归还
事务 ThreadLocal 还没清理
```

所以项目最终选择：

```text
事务 Bean 方法直接 return
```

调用方拿回控制权时：

```text
commit 完成
连接归还
ThreadLocal 清理
```

然后再做网络 IO。

这个边界非常清晰：

```text
事务内：
只做本地数据库操作

事务外：
MQ / Feign / 网络调用
```

---

# 九、P1 段2：从“调用下游”改成“发布事件”

Feign 模式是：

```text
Pay
↓
Order
↓
Pay
↓
Ticket
```

Pay 需要知道两个服务。

MQ 模式则变成：

```text
Pay
↓
PAY_SUCCESS

RocketMQ

↓
Order Consumer
↓
Order PAID
↓
ORDER_PAID

RocketMQ

↓
Ticket Consumer
↓
Ticket PAID
Seat SOLD
```

这是**链式事件驱动**，不是简单扇出。当前完整流程在文档中已经按源码列出来。

---

# 十、为什么不能 Pay 同时给 Order 和 Ticket 发 `PAY_SUCCESS`

因为这里存在业务顺序。

假如扇出：

```text
        ┌→ Order
Pay ────┤
        └→ Ticket
```

Ticket 可能先消费：

```text
Seat:
LOCKED → SOLD

Ticket:
UNPAID → PAID
```

但 Order 还：

```text
PENDING
```

这时超时任务可能：

```text
扫描 Order
↓
发现 PENDING
↓
CLOSED
↓
释放 Ticket
```

就和支付事实冲突。

所以当前项目用：

```text
Pay
↓ PAY_SUCCESS
Order
↓ ORDER_PAID
Ticket
```

只有：

```text
Order 成功处理
```

才产生：

```text
ORDER_PAID
```

因此 Ticket 根本不可能先于 Order 正常消费。

这不是使用 RocketMQ 的“顺序消息功能”，而是：

> **通过事件依赖关系保证业务顺序。**

这一点比单纯使用 `MessageListenerOrderly` 更有业务意义。

---

# 十一、为什么 `ORDER_PAID` 最终只带 `orderSn`

原计划想让 Order：

```text
查询自己的 OrderItem
↓
把所有座位坐标塞进 ORDER_PAID
```

最终没这么做。

最后：

```json
{
  "orderSn": "..."
}
```

Ticket 消费后：

```text
按 orderSn
↓
查询自己的 t_ticket
↓
得到：
train
区间
seatType
carriage
seatNumber
```

这样有三个好处。

第一：

> **Ticket 使用自己拥有的数据。**

第二：

> 消息体更小，事件只表达“该订单已支付”这个事实。

第三，也是最关键的：

项目已经有：

```text
TicketOrphanRecoveryJob
```

它本来就是：

```text
Order 已支付
Ticket 未支付
↓
根据 orderSn 查 t_ticket
↓
恢复 Ticket
```

所以 MQ 正常路径与补偿路径现在共享：

```text
同一个数据来源
```

不会出现：

```text
MQ 路径信 Order 的坐标
补偿路径信 Ticket 的坐标
```

这种两套事实来源。

文档也明确说明这是最终方案相对原计划的重要变化。

---

# 十二、MQ 引入后，最核心的新问题其实是“可靠性”

一个非常常见的错误理解：

```text
用了 RocketMQ
=
消息不会丢
```

不是。

现在链路：

```text
Pay DB
↓
Producer
↓
Broker
↓
Order Consumer
↓
Order DB
↓
Producer
↓
Broker
↓
Ticket Consumer
↓
Ticket DB
```

任何一步都可能失败。

所以 P1 真正有价值的是：

> 不是“接入 RocketMQ”，而是把失败窗口一个个补上。

---

# 十三、Pay 生产端：为什么必须有本地消息表

最经典的问题：

```text
Pay：
UPDATE status = PAID
COMMIT

↓
准备发 MQ

↓
进程挂了
```

于是：

```text
Pay = PAID
```

但：

```text
PAY_SUCCESS 根本没有进入 Broker
```

MQ 自己无法重试，因为：

> Broker 根本不知道这条消息存在。

于是引入：

```text
t_pay_notify_message
```

Pay 本地事务现在一次做：

```text
BEGIN

UPDATE t_pay
WAIT_PAY → PAID

INSERT t_pay_notify_message
status = PENDING

COMMIT
```

这两个操作同事务。

因此：

```text
只要 Pay=PAID 成功提交
```

数据库里一定还有：

```text
“PAY_SUCCESS 尚待发送”
```

这个事实。

---

# 十四、本地消息表具体存什么

核心字段：

```text
pay_sn
order_sn
event_type
payload
status
retry_count
next_retry_time
```

其中：

```text
status=0
```

代表：

> 还欠 MQ 一次发送。

```text
status=10
```

表示：

> 已获得 MQ `SEND_OK`。

唯一键：

```sql
UNIQUE(pay_sn, event_type)
```

保证同一支付事件不能重复插入。

扫描索引：

```sql
(status, next_retry_time)
```

服务于：

```sql
WHERE status = 0
AND next_retry_time <= now
```

文档里的实际 DDL就是这样设计的。

---

# 十五、本地消息发送失败以后发生什么

立即发送失败：

```text
SEND ERROR
```

并不会把 Pay 回滚。

而是：

```text
消息 status 仍然 = 0
retry_count++
next_retry_time 往后推
```

退避规则近似：

```text
1s
2s
4s
8s
...
最大 60s
```

然后：

```text
PayNotifyMessageScanJob
```

每隔一段时间扫描。

因此生产侧可靠性链路是：

```text
立即发送
↓
失败
↓
消息表还在
↓
定时扫描
↓
重发
```

---

# 十六、这就是 Outbox 思想

你可以把：

```text
t_pay_notify_message
```

理解成：

> **数据库里的待办任务。**

它表达：

```text
这笔业务已经成功，
但还有一个 PAY_SUCCESS 需要传播。
```

所以本地消息表真正解决的是：

> **业务事务和消息发送不是原子操作的问题。**

不是让：

```text
MySQL + RocketMQ
```

真的变成一个 ACID 事务。

而是：

```text
业务事实
+
待发送事实
```

先原子落库。

然后靠重试最终完成。

---

# 十七、Broker 层做了什么

当前版本：

```text
rocketmq-client 5.5.0
broker 5.5.1
```

并且：

```text
flushDiskType = SYNC_FLUSH
```

所以 Producer 得到 `SEND_OK` 前，消息已经同步刷盘。

但：

```text
brokerRole = ASYNC_MASTER
```

而且当前只有单 Broker。

所以不能说：

> “已经做了主从高可用。”

真实情况是：

```text
Broker 落盘可靠性
✅

主从复制
❌ 未部署
```

这个边界要面试时主动讲。

---

# 十八、消费端可靠性：为什么不能 catch 后直接 return

消费者正常处理：

```text
return CONSUME_SUCCESS
```

RocketMQ认为：

> 消费成功。

处理失败：

```text
抛异常
```

RocketMQ会：

```text
重试
```

所以这种写法危险：

```java
try {
    doBusiness();
} catch (Exception e) {
    log.error(...);
}

return CONSUME_SUCCESS;
```

因为：

```text
业务实际失败
但 MQ 认为成功
```

消息就真的丢了。

所以当前两个主消费者刻意不吞异常。

---

# 十九、但为什么有些情况反而必须 ACK

这是很有价值的一个细节。

比如 Ticket 收到：

```text
ORDER_PAID
```

结果发现：

```text
ticket 已经 CANCELED
```

如果你抛异常：

```text
RocketMQ 重试
↓
还是 CANCELED
↓
再重试
↓
还是 CANCELED
```

永远不会变好。

最后成为：

> 毒消息。

所以当前逻辑：

```text
已非 UNPAID
↓
直接当作无需处理
↓
ACK
```

但如果：

```text
这个 orderSn 连 ticket 都查不到
```

这可能是：

```text
数据尚未准备
瞬时异常
```

所以抛异常，让 MQ 重试。

这个判断标准非常好：

> **重试以后，这个状态有可能变好吗？**

有可能：

```text
retry
```

不可能：

```text
ACK / 进入业务异常处理
```

---

# 二十、为什么重复消息不会出问题

RocketMQ 的可靠投递天然可能重复。

但项目没有额外使用：

```text
Redis SETNX
```

而是复用原状态机。

Order：

```sql
UPDATE t_order
SET status = PAID
WHERE order_sn = ?
AND status = PENDING;
```

第一次：

```text
affectedRows=1
```

第二次：

```text
affectedRows=0
```

直接视为已处理。

Ticket：

```text
UNPAID → PAID
LOCKED → SOLD
```

也都带原状态条件。

所以：

```text
重复消息
↓
重复执行
↓
条件不匹配
↓
空操作
```

这就是**业务幂等**。

文档甚至有实测：同一消息实际被消费 2 次后，订单状态、车票数量都没变化，也没有重复座位。

---

# 二十一、一个非常关键的 Ticket 幂等细节

Ticket 不是直接：

```text
UPDATE seat SOLD
```

而是：

```text
先：
Ticket UNPAID → PAID

只有成功后：
Seat LOCKED → SOLD
```

如果 ticket 本身已经不是 UNPAID：

```text
continue
```

不会碰座位。

这是为了防止：

> 老订单迟到的支付消息，误操作一个已经重新分配给新订单的座位。

因为 Ticket 带：

```text
orderSn
```

有明确归属。

而 Seat 更像：

```text
物理座位坐标
```

所以：

> 先验证属于这笔订单的 Ticket，再去操作 Seat。

这个设计很值得讲。

---

# 二十二、Order→Ticket 的消息发送失败怎么办

这也是整个链路中很值得问的地方。

假设：

```text
PAY_SUCCESS
↓
Order Consumer

Order：
PENDING → PAID
成功

↓

准备发 ORDER_PAID
失败
```

此时状态：

```text
Order = PAID
Ticket = UNPAID
Seat = LOCKED
```

消费者没有正常返回。

因此：

```text
PAY_SUCCESS 不 ACK
↓
RocketMQ 重新投递
```

下一次：

```text
Order 已经 PAID
↓
条件更新为空操作
↓
继续尝试发 ORDER_PAID
```

所以：

> Order 状态机的幂等，使得整个 Consumer 方法可以安全从头重试。

这非常漂亮。

---

# 二十三、如果 MQ 重试 16 次都失败呢

就会进入：

```text
%DLQ%consumerGroup
```

死信队列。

当前项目已经实现：

```text
DLQ watcher
```

它会：

```text
计数
+
ERROR 日志
```

但是注意：

> **没有自动重放。**

而且当前并没有真实等待完整 16 次退避直到自动进死信，只验证了：

```text
reconsumeTimes 0 → 1
```

以及：

```text
人工向 DLQ topic 发消息
→ watcher 能收到
```

所以这里面试必须区分：

```text
重试机制已验证
DLQ watcher 已验证
完整 16 次耗尽路径未验证
```

---

# 二十四、为什么 Ticket 还有 `TicketOrphanRecoveryJob`

MQ 重试本身还不是业务最终兜底。

例如：

```text
Order = PAID

但所有 ORDER_PAID 消息都因为某种异常彻底没处理成功
```

Ticket 仍：

```text
UNPAID
```

那么业务层还能通过：

```text
TicketOrphanRecoveryJob
```

发现：

```text
一张老的 UNPAID Ticket
↓
查询对应 Order
↓
Order 已经 PAID
↓
主动调用 TicketCallback
```

完成恢复。

这就是：

> **消息机制失败以后，直接根据业务事实重新对齐状态。**

---

# 二十五、所以你这个项目现在有三层完全不同的“恢复”

这是 P1 最值得掌握的部分。

### 第一层：本地消息补发

解决：

```text
业务已经 commit
但消息还没进 Broker
```

机制：

```text
t_pay_notify_message
+
PayNotifyMessageScanJob
```

---

### 第二层：RocketMQ Retry

解决：

```text
消息已经进入 Broker
但 Consumer 失败
```

机制：

```text
reconsume
→ retry
→ DLQ
```

---

### 第三层：业务补偿

解决：

```text
消息系统已经没法保证了
业务状态仍不一致
```

机制：

```text
TicketOrphanRecoveryJob
```

所以以后不要把三个都叫：

> “重试机制”。

它们完全不是一层东西。

---

# 二十六、`PayNotifyCompensateJob` 为什么 MQ 模式反而停掉了

这个也是最终实现和原计划的一个重要差异。

原来 Feign 模式：

```text
t_pay.notify_status
```

代表：

```text
Order/Ticket 是否同步通知完成
```

所以：

```text
PayNotifyCompensateJob
```

扫描：

```text
PAID + notify_status=0
```

然后重新走 Feign。

但 MQ 模式中：

```text
notify_status
```

不会由 MQ 路径更新。

如果继续开启：

```text
所有 MQ 支付单
notify_status 永远是 0
```

于是 Job 会：

```text
把所有支付成功单
重新走一遍同步 Feign
```

等于：

> 刚拆掉 MQ，又偷偷把同步链路接回来了。

所以最终实现：

```java
if (notifyMode == mq) {
    return;
}
```

这是很正确的修正。

---

# 二十七、P1 为什么保留 `feign / mq` 双模式

配置：

```yaml
my12306:
  pay:
    notify-mode: feign
```

默认仍是 Feign。

这样做两个用途：

### 第一：回归保险

MQ 出现问题：

```text
改配置
↓
退回原链路
```

而不是必须回滚大量代码。

### 第二：A/B 测试

同一套业务：

```text
feign
vs
mq
```

可以用相同环境比较。

这个设计非常适合项目演进。

---

# 二十八、P1 的性能结果应该怎么理解

文档里最值得注意的一件事：

最开始端到端 S3 数据居然显示：

```text
MQ 更慢
```

甚至慢很多。

但最后发现：

> 测错指标了。

因为测试流程：

```text
支付
↓
每 500ms 查询一次 Order
直到变 PAID
```

Feign：

```text
pay callback 返回之前
Order 已经同步 PAID

↓
第一次查询马上看到
```

MQ：

```text
pay callback 很快返回
但 Order 是异步更新

↓
客户端等下一轮 500ms 轮询
```

于是：

> MQ 虽然接口更快，但测试把“最终一致等待”算进了耗时。

重新直接测试：

```text
支付 callback 本身
```

得到：

```text
Feign ≈ 161ms
MQ ≈ 114ms
```

下降约：

```text
29%
```

这个故事非常适合面试，因为它体现：

> **性能测试首先要确保指标和你要证明的问题一致。**

---

# 二十九、P0/P1 合起来体现了一个共同设计原则

其实它们看起来一个是索引，一个是 MQ，但底层思想很像：

> **缩短临界区。**

P0：

```text
Redisson 锁临界区
```

减少：

```text
SQL 日志
无效扫描
```

P1：

```text
数据库事务临界区
```

减少：

```text
Feign 网络 IO
MQ 网络 IO
```

所以可以总结成：

```text
锁里只做必须做的事

事务里只做必须原子的本地写

跨服务操作尽可能放到边界之外
```

这个是你项目里已经重复出现两次的工程规律。

---

# 三十、P1 目前还没完全解决什么

现在千万不要把项目包装成“高可靠支付系统”。

目前仍然有明显边界：

### 1. 没退款

最重要。

```text
Pay = PAID
Order = CLOSED
```

目前业务数据不会乱，也不会超卖，但：

```text
钱没退
```

只记日志。

---

### 2. 没真正对账

目前有：

```text
消息重试
本地消息补发
孤儿 Ticket 恢复
```

但没有：

```text
支付渠道真实流水
vs
t_pay
vs
t_order
```

周期性全链路对账。

---

### 3. DLQ 不会自动重放

目前：

```text
DLQ → 日志 + counter
```

缺少：

```text
人工处理后台
重放接口
死信持久化表
```

---

### 4. 单 Broker

只保证：

```text
SYNC_FLUSH
```

没有主从容灾。

---

### 5. 多实例没验证

包括：

```text
Scheduled 重复扫描
Snowflake workerId
多个 Pay 实例重复发送
```

都还没真正验证。

---

# 三十一、你现在应该如何理解 P0/P1 的“功能”

不要把它们理解成：

```text
P0：
加索引

P1：
加 MQ
```

更准确的是：

## P0

```text
性能诊断系统
+
锁临界区优化
+
MySQL 索引实战
```

解决：

> 高并发占座时临界区耗时过长。

---

## P1 段1

```text
事务边界重构
```

解决：

> 本地数据库事务被远程调用拉长。

---

## P1 段2

```text
事件驱动支付通知
+
Transactional Outbox
+
消费幂等
+
MQ Retry/DLQ
+
业务补偿
```

解决：

> 支付结果传播的同步耦合、下游故障传播以及消息可靠性问题。

---

# 三十二、如果让我给你当前 P0/P1 的面试价值排序

最值得你接下来重点学透的是：

1. **P1 的三层可靠性**：本地消息补发 / MQ retry / 业务补偿。
2. **P1 的事务边界**：为什么独立 Bean 比把远程调用放事务里好。
3. **P1 的链式事件顺序**：为什么 Order 一定先于 Ticket。
4. **P1 的业务幂等**：为什么不用 Redis SETNX。
5. **P0 的 EXPLAIN + 联合索引实测**。
6. **P0/P1 共同的“缩短临界区”设计规律。
7. 性能测试中“指标选错导致结论反转”的排查过程。
8. RocketMQ 环境和 Topic/DLQ 的工程坑。

接下来如果继续按我们之前的复习方式，我建议下一步不是再看整个文档，而是从 **P1 的“本地消息表为什么能保证可靠发送”** 开始，一层层深入到 MQ 重试、幂等、最终一致性。这部分是现在整个项目里最值得面试讲的新增内容。