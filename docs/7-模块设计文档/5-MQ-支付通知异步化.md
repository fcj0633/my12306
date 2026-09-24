继续打穿支付链路。

前一份《3-订单支付-支付.md》里已经说过：

> 当前项目中，支付成功后的通知确实是 **Pay → Order → Ticket 的同步串行 Feign**，还没有切成 MQ。

这一份就讲**把它切成 MQ 的全过程**。

但先别急着看 Producer 和 Consumer。先把三件事想清楚：

> **① 原来的写法到底哪里不对？（以及哪里其实是对的，别讲错）**
>
> **② 可靠性到底靠什么？靠 MQ 吗？**
>
> **③ 为什么最后用的是"链式"，而不是"扇出"？**

把这三个问题想清楚，后面看到 topic、tag、消费者组、重试、死信、本地消息表，就都不会变成背 API。

---

# 一、先看清楚：原来的通知方式到底哪里不对

## 1.1 原来的链路

支付成功以后，`PayServiceImpl.notifyPayResult` 做三件事：

```text
Pay
 │
 │ ① Feign 查订单详情（拿座位坐标）
 v
Order
 │
 │ ② Feign 回调 order：订单 PENDING_PAYMENT → ALREADY_PAID
 v
Pay
 │
 │ ③ Feign 回调 ticket：座位 LOCKED → SOLD，车票 UNPAID → PAID
 v
Ticket
 │
 v
Pay 把 notify_status 置为 1
```

**串行、同步、写死在支付服务里。**

## 1.2 三个问题

| # | 问题 | 后果 |
| --- | --- | --- |
| 1 | **串行同步**：两次 Feign 依次执行 | 支付回调的响应时间 = 两个下游之和 |
| 2 | **强耦合**：支付服务必须知道"order 有个 pay-callback 接口、ticket 有个 pay-callback 接口" | 新增一个下游（比如积分服务）要改支付服务 |
| 3 | **这三个远程调用都在事务里** ★ | 见下 |

第 3 条要专门解释，因为它最隐蔽。

## 1.3 第 3 条为什么隐蔽：事务沿调用栈传播

看 `PayServiceImpl` 的代码结构：

```java
@Override
@Transactional(rollbackFor = Exception.class)        // ← payCallback 有事务
public boolean payCallback(PayCallbackReqDTO requestParam) {
    ...
    return notifyPayResult(requestParam.getPaySn());  // ← 第 164 行，调用点
}

@Override
public boolean notifyPayResult(String paySn) {        // ← 注意：这个方法【没有】@Transactional
    ...
    orderRemoteService.payCallbackOrder(...);         // ← 远程调用 1
    ticketRemoteService.payCallback(...);             // ← 远程调用 2
}
```

**单看 `notifyPayResult`，它是没有事务的。** 所以扫代码时很容易认为"这个方法不在事务里"。

但 **Spring 的事务是沿着调用栈传播的**：

```text
payCallback （有 @Transactional，事务在这里开始）
    └── notifyPayResult （自己没有注解，但它是被有事务的方法调用的）
            └── 三次远程调用 → 全部在这个事务里
```

**通用教训（可以直接迁移到面试回答里）：**

> 判断"某个操作在不在事务里"，**不能只看当前方法有没有注解，要往外看调用方**。
>
> 反过来说：判断"事务里有没有远程调用"，**要沿着调用栈往下查**，而不是只扫带 `@Transactional` 的方法。

这条认知就是 P1 最有价值的产出之一 —— 因为购票链路在 D4 已经修过**完全同构**的问题：

```text
D4 修的是：购票链路把两次 Feign 放在本地事务里
P1 发现的是：支付链路有一模一样的反模式，只是藏得更深
```

## 1.4 ⚠️ 但有一件事必须纠正，否则面试会讲错

很多资料（包括本项目早期的一份分析文档）会把第 3 条的后果说成：

> "远程调用失败 → 整个事务回滚 → **支付单的'已支付'状态被撤销** → 但钱已经扣了 → 补偿任务再也扫不到这笔支付单，订单永远不会变成已支付"

**这个说法是错的。**

看 `notifyPayResult` 的结尾：

```java
} catch (Throwable ex) {
    log.error("支付结果通知下游失败，paySn={}，保留未完成状态等待补偿重推", paySn, ex);
    return false;      // ← 关键：把异常吞掉了
}
```

那个 `catch (Throwable)` 把**所有**异常都吃掉了 —— 包括它自己在上面的 `if` 里抛出的
`ServiceException("通知订单服务失败")`。所以**异常根本传不到 `payCallback`**，
事务照常提交，`t_pay` 保持 `PAID`，`notify_status` 留在 0，交给补偿任务重推。

而且 `PayServiceImpl` 的**类注释第 3 条**其实早就写明了这件事：

> 支付成功后的下游通知失败不回滚支付单，只把通知状态置为未完成，由补偿任务重推。

**由此得到一个很重要的推论：**

> **任何"停掉下游服务、看支付状态会不会回滚"的验证都是无效的。**
> 因为改前改后 `t_pay` 都会是 `PAID`，这个测试证明不了任何东西。

## 1.5 所以 P1 到底在修什么

把上面理清之后，真正成立的收益只有两条：

| 真正成立的 | 不成立的 |
| --- | --- |
| **`t_pay` 的行锁与数据库连接被三次远程 I/O 撑大**；事务边界被远程调用污染 | ~~事务回滚撤销"已支付"事实~~ |
| **响应时间 = 两个下游之和**（串行同步） | ~~补偿任务扫不到这笔支付单~~ |

**并且要说清量级**：D4 那次缩锁之所以能换来 S3 P99 下降 30.4%，
是因为它保护的是**100 个并发抢的同一批座位行**；
而支付回调 QPS 很低、`t_pay` 同一行几乎无并发争用。
**所以这里是"事务边界修复"，不是"解决了一个正在流血的瓶颈"。**

> "同构"指的是**反模式相同**，不指**危害相等**。这个区分在面试里很重要 ——
> 说成"又修了一个严重的钱账不一致 bug"就过头了。

**结论：P1 是两件事，不是一件。**
① 事务边界修复（把远程调用移出事务）；
② 真正的异步化（把同步 Feign 换成 MQ）。

---

# 二、为什么这里正好是 MQ 的场景

MQ 的三大作用不是三个词，而是**三种"改造前后对比"**。

## 2.1 解耦

```text
改造前：支付服务 → 知道 order 的接口 → 知道 ticket 的接口

改造后：支付服务 → 只知道"我要发一条【支付成功】事件"
                  谁关心这件事，谁自己订阅
```

**判断标准很简单**：新增一个"积分服务"要消费支付成功，**支付服务要不要改**？

改造前要改（加一个 Feign 接口）。改造后一行都不用改。

## 2.2 异步

```text
改造前：响应时间 = 调用 order + 调用 ticket
改造后：响应时间 = 一次本地事务 + 一次消息发送
```

这是用户能感知的差异。

## 2.3 削峰

```text
改造前：支付回调的 QPS 直接压到 order 和 ticket 上
改造后：消息堆在 Broker 里，消费者按自己的节奏消费
```

下游可以独立扩容，也可以在高峰期降速而不影响支付。

## 2.4 ⚠️ 但要说清楚：本项目最终用的是"链式"，不是"扇出"

这是最容易讲错的地方。

教科书式的 MQ 扇出长这样：

```text
                ┌──> Order  消费者组
一次事件 ────────┤
                └──> Ticket 消费者组
```

**但本项目做的是链式：**

```text
Pay ──PAY_SUCCESS──> Order ──ORDER_PAID──> Ticket
```

**为什么不用扇出？因为顺序需求是真实的。**

假设让 order 和 ticket **并行**消费同一条 PAY_SUCCESS：

```text
ticket 先改：座位 LOCKED → SOLD，车票 UNPAID → PAID
order 还没改：订单仍是 PENDING_PAYMENT

此时超时关单任务扫到了这笔订单
  → 关单
  → 释放座位（SOLD → AVAILABLE）

于是：座位被释放了，但用户已经付钱了 → 【超卖】
```

反过来，**只要订单先变成 ALREADY_PAID，超时关单任务就扫不到它了，就安全**。

所以链式的价值是：

> **把"order 成功"变成"ticket 开始"的前提。**

**代价要如实说明**：链式拿顺序，但**放弃了"一个事件多个独立消费者"的扇出形态** ——
两个下游变成串行依赖，ticket 的消费以 order 成功为前提。

---

# 三、先定一个大方向：可靠性到底靠什么

## 3.1 一句最重要的话

> **可靠性不在 MQ 里。**

消息中间件只保证**尽力投递**。消息会丢、会重复、会乱序 —— 这些都是常态，不是异常。

可靠性来自你自己的设计：

```text
本地落状态  +  补偿
```

## 3.2 本项目其实早就有这张"本地消息表"了

这是个很值得讲的点：

| 你以为要新建的东西 | 其实项目里已经有简化版 |
| --- | --- |
| 本地消息表 | `t_pay.notify_status` —— 记录"这笔支付通知过下游没有" |
| 消息表的补偿扫描任务 | `PayNotifyCompensateJob` —— 扫到未完成的通知就重推 |

所以 P1 的改造**不是"从零引入 MQ"**，而是：

> **把"同步 Feign 推送"换成"异步 MQ 投递"，而可靠性的保证机制（本地落状态 + 补偿扫描）保持不变。**

**这个表述在面试里非常强**，因为它说明你理解：

> MQ 解决的是**传输方式**，不是**可靠性本身**。

## 3.3 为什么不用 RocketMQ 的事务消息

面试可能追问"为什么不用事务消息"，标准答法：

> 事务消息能解决"本地事务与消息发送的原子性"，但它的可靠性**依赖 RocketMQ 的半消息 + 回查机制**——
> 换一个 MQ 就要重做一遍。
>
> 本地消息表用**普通消息**就能达到同样效果，而且消息内容落库之后，
> 排查问题时能直接看到发的原文。代价是多一张表 + 一个扫描任务。
>
> 在我的场景里支付回调的 QPS 不高，本地消息表的额外开销可以忽略，所以我选了它。

---

# 四、本地消息表：`t_pay_notify_message`

## 4.1 表结构

```sql
CREATE TABLE `t_pay_notify_message`
(
    `id`              bigint unsigned NOT NULL AUTO_INCREMENT,
    `pay_sn`          varchar(64),                  -- 支付流水号
    `order_sn`        varchar(64),                  -- 订单号
    `event_type`      varchar(32),                  -- 事件类型：PAY_SUCCESS
    `payload`         varchar(1024),                -- 消息体（JSON）
    `status`          int(3) DEFAULT 0,             -- 0 待发送 / 10 已发送
    `retry_count`     int(11) DEFAULT 0,
    `next_retry_time` datetime,                     -- 退避用
    `create_time`     datetime,
    `update_time`     datetime,
    `del_flag`        tinyint(1) DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pay_sn_event` (`pay_sn`, `event_type`),
    KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE = InnoDB;
```

## 4.2 逐项说明

### `UNIQUE (pay_sn, event_type)` —— 幂等的最后一道防线

渠道会重复回调。如果两次回调并发进来，两个线程都可能走到"插入消息"这一步。

有了这个唯一键，**第二次插入会被数据库挡掉**。

> 注意这里和业务侧的幂等是**两个层面**：
> - 业务侧：`UPDATE t_pay ... WHERE status = WAIT_PAY` 的条件更新（影响 0 行 = 已处理）
> - 消息侧：`UNIQUE(pay_sn, event_type)`（插入失败 = 已写过）

### `payload` 落库 —— 为什么不只存个标记

存消息原文的好处是**排查时能直接看**：

```sql
SELECT payload FROM t_pay_notify_message WHERE pay_sn = '...';
{"orderSn":"210315...","paySn":"2103...","payTime":"2026-09-25 00:11:13"}
```

不依赖中间件控制台，不依赖日志。

### `KEY (status, next_retry_time)` —— 扫描任务专用

扫描任务查的是"待发送 + 到时间了"，正好走这个复合索引。

## 4.3 写入时机：与 `t_pay` 在**同一个事务**里

这是本地消息表的精髓。看 `PayCallbackTxService.markPaid`：

```java
@Transactional(rollbackFor = Exception.class)          // 事务边界在这里
public boolean markPaid(PayCallbackReqDTO requestParam) {
    ...
    int affectedRows = payMapper.update(updateDO, ...); // ① t_pay 0 → 10
    if (affectedRows == 0) { ...; return true; }        //    并发时直接返回
    if (MQ_NOTIFY_MODE.equalsIgnoreCase(notifyMode)) {
        insertPendingNotifyMessage(...);                // ② INSERT 待发送消息
    }
    return true;
}
```

**① 和 ② 要么一起成功，要么一起失败。**

这就解决了"事务消息"要解决的那个问题 —— 只不过是用普通消息 + 一张表实现的。

> ⚠️ 一个容易踩的细节：第 ② 步**必须在 `affectedRows > 0` 时才执行**。
> 并发回调时，输的那个线程如果也去插入，会撞 `UNIQUE` 键 → **把整个事务回滚掉**。

---

# 五、生产端：从"事务内发"到"提交后发 + 兜底重发"

## 5.1 先看一个我实际踩过的坑：`afterCommit` 不够用

最初的设计是：在 `payCallback` 里注册 `afterCommit` 回调，在回调里发消息。

**看起来对，但实测会发现两个问题。**

先看 Spring 事务同步的执行顺序：

```text
doCommit()                        ← 事务在这里提交（行锁释放）
   ↓
triggerAfterCommit()              ← ★ afterCommit 回调在这里
   ↓
triggerAfterCompletion()
   ↓
cleanupAfterCompletion()          ← ★ 连接归还、ThreadLocal 清理在这里
```

**`afterCommit` 里：**
- 行锁**确实**已经释放了（提交完成）✅
- 但**数据库连接还被占着**（要到 `cleanupAfterCompletion` 才归还）❌
- 而且 `TransactionSynchronizationManager.isActualTransactionActive()` **仍然返回 `true`** ❌

所以用 `afterCommit` 的话，"连接提前释放"做不到，**而且你连一个能自证的断言都写不出来**。

## 5.2 实际用的办法：把事务单独放进一个 Bean

改成 D4 已经确立的模式 —— **把事务内部分单独做成一个 Bean**：

```java
@Service
public class PayCallbackTxService {                  // ← 事务在这里
    @Transactional(rollbackFor = Exception.class)
    public boolean markPaid(PayCallbackReqDTO req) { ... }
}

@Service
public class PayServiceImpl implements PayService {
    @Override
    public boolean payCallback(PayCallbackReqDTO requestParam) {   // ← 本方法【不带】@Transactional
        boolean recorded = payCallbackTxService.markPaid(requestParam);   // 事务在这行返回时【完整结束】
        if (recorded) {
            if (MQ_NOTIFY_MODE.equalsIgnoreCase(notifyMode)) {
                sender.sendByPaySn(requestParam.getPaySn());   // 事务外
            } else {
                notifyPayResult(requestParam.getPaySn());      // 事务外
            }
        }
        return recorded;
    }
}
```

**为什么这样一定对**：调用方是在**事务 Bean 的方法返回之后**才执行发送的。
Bean 方法返回 ⇒ 事务已提交 ⇒ 连接已归还 ⇒ ThreadLocal 已清理。**不需要依赖任何回调时机。**

> 这与 D4 为购票链路建立的 `PurchaseTicketTxService` 是**同一个模式**，属沿既有约定而非新造轮子。

## 5.3 用一对日志把事务边界夹住（可自证）

代码里留了两行**互为镜像**的断言日志：

| 位置 | 期望值 |
| --- | --- |
| `PayCallbackTxService.markPaid`（事务内） | `事务活动状态=true` |
| `PayServiceImpl.notifyPayResult`（远程调用前） | `事务活动状态=false` |

实测同一次支付（`paySn=2103145463976390656`）：

```text
支付单状态推进（事务内）事务活动状态=true （期望 true）
支付结果通知开始，      事务活动状态=false（期望 false）
```

**事务内真、事务外假** ⇒ 远程调用确实落在事务之外。
而且从线程名 `[nio-9004-exec-7]` 能看出**还是同一个 Tomcat 线程** ——
所以这一步只是修了事务边界，**响应时间不会变**；响应时间的变化要等 MQ 那一步。

## 5.4 发送器：`syncSend` + 判断 `SEND_OK`

```java
SendResult result = producer.send(mqMessage);            // 同步发送
if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
    throw new IllegalStateException("MQ 未返回 SEND_OK");
}
markSent(message.getId());                               // 条件更新 status 0 → 10
```

**为什么是同步发送？** 因为要拿到"Broker 确认"。

配合 Broker 侧的配置（见附录 A），`SEND_OK` 意味着**消息已经落盘**。这就是"可靠性三端"里的**生产端确认**。

**失败怎么办？** 不抛给用户，而是：

```java
} catch (Throwable ex) {
    markRetry(message);          // retry_count+1，并设置退避时间
    return false;                // 消息保持 status=0，等扫描任务
}
```

**这就是"低延迟靠即时发送、可靠性靠扫描任务"的分工。**

## 5.5 扫描任务：把没发出去的消息补上

```java
@Scheduled(initialDelayString = "...", fixedDelayString = "${my12306.pay.notify-message-scan-interval-ms:10000}")
public void resendPending() {
    List<PayNotifyMessageDO> pending = messageMapper.selectList(
        Wrappers.lambdaQuery(PayNotifyMessageDO.class)
            .eq(PayNotifyMessageDO::getStatus, PENDING)
            .lt(PayNotifyMessageDO::getRetryCount, maxRetry)
            .le(PayNotifyMessageDO::getNextRetryTime, now)     // ← 退避
            .orderByAsc(PayNotifyMessageDO::getId)
            .last("LIMIT " + batchSize));
    ...
}
```

**两个实现细节值得注意：**

1. **插入时就把 `next_retry_time` 设成当前时间**。
   如果留 `NULL`，`le(next_retry_time, now)` 永远匹配不到它 —— 消息会永远躺在表里发不出去。

2. **退避是必要的**。下游持续不可用时，不退避会让扫描任务每轮白跑一遍并刷满日志。
   实现是 `min(2^retryCount, 60) 秒`。

还有第三个方法 `reportStuck()`：把"超过重试上限仍未发出"的消息计数告警。
**这类消息重试也没用了，必须人工介入** —— 所以它单独暴露一个计数器，而不是混在失败里。

---

# 六、链式事件驱动：三个服务的分工

## 6.1 全景

```text
                          topic: my12306_pay_result
                          ┌─────────────────────────┐
                          │  tag=PAY_SUCCESS        │   tag=ORDER_PAID
                          └─────────────────────────┘
                                    │                     ▲
       ① 投递                        │                     │ ② 发出
   ┌──────────┐                     ▼                     │
   │   Pay    │ ─────────────────> ┌──────────┐ ──────────┘
   │ 生产端    │                    │  Order   │
   └──────────┘                    │ 消费者+生产│
                                   └──────────┘
                                        │ ② 发出 ORDER_PAID
                                        ▼
                                   ┌──────────┐
                                   │  Ticket  │
                                   │  消费者   │
                                   └──────────┘
```

**注意 Order 是"既消费又生产"**，这是链式必然的结果。
Ticket 是**末端**：只消费，不再往下发。

## 6.2 一个 topic + 两个 tag，而不是两个 topic

```java
public static final String TOPIC = "my12306_pay_result";
public static final String TAG_PAY_SUCCESS = "PAY_SUCCESS";
public static final String TAG_ORDER_PAID  = "ORDER_PAID";
```

tag 过滤是 **Broker 端**完成的，所以一个 topic 更省资源，
也让"支付结果"这一族事件在控制台里聚在一起。

> ⚠️ 项目没有共享模块（连 `Result` 和 DTO 都是各服务各一份），
> 所以这套常量在 pay / order / ticket 里**各有一份，值必须保持一致**。
> 这是沿用项目既有约定的取舍，不是疏漏 —— 面试被问到要主动说明。

## 6.3 ★ 消息体里为什么只有 `orderSn`（这是本项目的一个关键设计）

看 `OrderPaidMessage`：

```java
public class OrderPaidMessage implements Serializable {
    private String orderSn;      // ← 只有这一个字段
}
```

**票务侧需要的坐标（车次/区间/席别/车厢/座号）它自己从 `t_ticket` 查。**

### 为什么这么设计

一开始我的方案是"让订单服务把座位坐标拼进消息里"，因为：

```text
OrderItemDO 有 carriageNumber / seatType / seatNumber
OrderDO     有 trainId / departure / arrival
→ 订单侧确实能拼出票务要的一切
```

**但读代码时发现项目里已经有一条更好的现成路径：**

```java
// ticket-services / TicketOrphanRecoveryJob.java
private TicketCallbackReqDTO buildCallback(String orderSn) {
    List<TicketDO> tickets = ticketMapper.selectList(...)     // ← 从【自己的】t_ticket 查
    ... 用 ticket.getCarriageNumber() / getSeatNumber() / getSeatType() 拼 seats
}
```

**这个任务就是"订单已支付但车票未支付"时的兜底修复。**

于是关键判断变成了：

> 如果 MQ 消费者走"订单拼坐标"、兜底任务走"票务查自己的表"，
> **两条做同一件事的路径就有了不同的坐标来源** —— 一旦两者出现分歧，
> 就会出现"正常路径和兜底路径行为不一致"的诡异 bug。
>
> **而兜底任务恰恰是通知丢失时才执行的那条路径** —— 最不该出现分歧的地方。

所以最终选择：**让消费者读自己的表，两条路径就一致了。**

### 顺带的好处

| 好处 | 说明 |
| --- | --- |
| 消息体极小 | 只承载"这笔订单已经付过钱了"这一个事实，不承载任何一方的业务视图 |
| 符合数据所有权 | 座位坐标本来就归票务管（购票时就是票务写的 `t_ticket`） |
| 少一次查询 | 支付侧不再需要 Feign 查订单详情（`loadOrderDetail` 从通知链路里彻底消失） |

---

# 七、消费端幂等：一行业务代码都没改

## 7.1 复用现成的方法

| 消费者 | 复用的方法 | 幂等机制 |
| --- | --- | --- |
| Order | `OrderService.payCallbackOrder` | `UPDATE t_order ... WHERE order_sn=? AND status=0`；影响 0 行 → 视为已处理 |
| Ticket | `TicketCallbackService.payCallback` | `UPDATE t_ticket ... WHERE ticket_status=UNPAID`；影响 0 行 → `continue` 跳过座位更新 |

**注意这两个方法就是 Feign 入口调用的同一个方法。MQ 只是换了触发源。**

```java
// order 侧消费者
orderService.payCallbackOrder(buildPayCallbackReq(payload));   // ← 和 Feign 入口调的是同一个方法
```

## 7.2 这一点在面试里要主动说

> "消息重复投递在我的系统里不会造成问题，因为我的消费端用的是**条件更新 + 影响行数判断** ——
>  这个能力在做 MQ 之前就已经有了，MQ 只是换了个触发源。"

**实测证据**（本项目真跑过）：人为把一条已发送的消息改回 `status=0`，让扫描任务重发：

| | 重发前 | 重发后 |
| --- | --- | --- |
| 订单状态 | 10 | 10 |
| 车票已支付数 | 1 | 1 |
| 重复座位数 | — | **0** |
| order 侧收到 PAY_SUCCESS 次数 | — | **2 次**（确实重复消费了）|

**同一条消息被消费两次，数据零变化。**

---

# 八、ACK 语义：一个必须避开的坑

## 8.1 规则

> **RocketMQ 的自动 ACK 语义 = 消费方法【正常返回】才 ACK。**
>
> 抛异常 = 不 ACK → 进重试队列。

## 8.2 错误写法

```java
// ❌ 绝对不要这样写
try {
    doSomething();
} catch (Exception ex) {
    log.error("失败了", ex);
    // 吞掉异常 → 方法正常返回 → 被当成【消费成功】→ 消息永久丢失
}
return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
```

**这不是"重试"的问题，是"消息直接没了"的问题。**

## 8.3 我的代码为什么刻意不包 `try/catch`

```java
@Override
public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
    for (MessageExt msg : msgs) {
        ...
        orderService.payCallbackOrder(buildPayCallbackReq(payload));   // 抛异常就让它抛
        publishOrderPaid(payload.getOrderSn());                        // 同上
        ...
    }
    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
}
```

代码注释里专门写了这一句：

> ⚠️ 不要在这里 catch 异常后 return。本方法刻意不包 try/catch —— **这是有意的，不是漏了**。

## 8.4 但有一种情况**必须** ACK，否则会造出"毒消息"

票务消费者里有一段反直觉的处理：

```java
List<TicketDO> unpaidTickets = tickets.stream()
        .filter(t -> TicketStatusEnum.UNPAID.getCode().equals(t.getTicketStatus()))
        .toList();

if (unpaidTickets.isEmpty()) {
    // 车票已不在"未支付"——例如订单此前已关闭、车票已被推进为 CANCELED。
    // 此时没有任何要推进的东西，属于【正常】情况。
    log.info("车票已非未支付状态，无需推进。orderSn={}", orderSn);
    continue;                     // ← 必须 ACK，不能抛
}
```

**为什么必须 ACK**：

如果这里抛异常，那么"**订单已关闭后收到支付成功**"这个**正常业务场景**
会变成一条永远重试、最终落进死信的**毒消息** —— 每轮压测都会制造一条死信。

**但车票一张都不存在时就必须抛**（数据异常，重试有机会自愈）：

```java
if (tickets.isEmpty()) {
    throw new IllegalStateException("该订单没有任何车票记录，orderSn=" + orderSn);
}
```

**判断标准：这个情况重试有可能变好吗？**
- 有可能变好 → 抛（进重试）
- 不可能变好 → ACK（正常结束）

## 8.5 顺序需求还带来一个"不用判断"的简化

订单消费者发出 `ORDER_PAID` 时，**不需要判断订单是不是真的变成了已支付**：

```java
orderService.payCallbackOrder(...);       // 可能是"订单已关闭"分支（不改状态）
publishOrderPaid(payload.getOrderSn());   // 仍然发
```

**为什么这样安全**：票务侧的 `updateTicketStatus` 带 `WHERE ticket_status = UNPAID` 条件，
订单之前若已关闭，车票早已被推进为 `CANCELED`，影响 0 行 → **跳过座位更新，不会把座位标成已售**。

**这同时也保证了 MQ 路径与 Feign 路径行为一致** —— 换成 MQ 之后没有引入行为差异，
两种模式才可以对等比较。

---

# 九、重试与死信

## 9.1 链路

```text
消费方法抛异常
    ↓
RocketMQ 自动重试（默认 16 次，指数退避：10s / 30s / 1m / 2m / ... / 2h）
    ↓ 16 次仍失败
进入死信 topic：%DLQ%<消费者组>
    ↓
死信监视消费者：计数 + 打印消息原文
    ↓
人工介入
```

## 9.2 实测证据

发一条"订单不存在"的 `ORDER_PAID`：

```text
收到 ORDER_PAID，orderSn=NONEXISTENT-ORDER-1，reconsumeTimes=0   ← 首次
收到 ORDER_PAID，orderSn=NONEXISTENT-ORDER-1，reconsumeTimes=1   ← 重试第 1 次
```

**`reconsumeTimes` 从 0 涨到 1** ⇒ 消费者的异常确实触发了重试。

而且**同一条批次里的另一条正常消息被正常消费了** ——
并发消费模式下消息互不影响，一条毒消息不会阻塞别人。

## 9.3 死信监视的实现与已知局限

用**普通消费者**订阅死信 topic，而不是引入 `rocketmq-tools` 里的 `DefaultMQAdminExt`：

```java
@PostConstruct
public void start() {
    String dlqTopic = "%DLQ%" + consumerGroup;
    try {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup + "-dlq-watcher");
        consumer.subscribe(dlqTopic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                meterRegistry.counter("my12306.mq.dlq.count").increment();
                log.error("收到死信！msgId={}，重试次数={}，body={}", ...);
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    } catch (Throwable ex) {
        // 死信 topic 在第一条死信出现前可能还不存在，启动失败不能拖垮应用
        log.warn("死信监视消费者启动失败（死信 topic 可能尚不存在），不影响主流程：{}", ex.getMessage());
    }
}
```

**⚠️ 已知局限（要主动讲，不要藏）**：

> 消费会把消息从死信队列**出队**，所以这里只做到"计数 + 日志留痕"，**不做重放**。
> 要支持重放得把死信持久化到表里。这是明确的取舍，不是没想到。

---

# 十、配置开关：`feign | mq`

## 10.1 为什么留开关

```yaml
my12306:
  pay:
    notify-mode: ${MY12306_PAY_NOTIFY_MODE:feign}
```

**默认 `feign`**，两个作用：

1. **回归保险** —— 不改配置时行为与 P1 之前**完全一致**，出问题能立刻退回去
2. **A/B 对照的前提** —— 两种模式跑同一套压测脚本，数字才可比

而且 `feign` 模式下 **MQ 相关的 Bean 根本不会创建**：

```java
@Configuration
@ConditionalOnProperty(name = "my12306.pay.notify-mode", havingValue = "mq")
public class RocketMqProducerConfig { ... }
```

**实测确认**：feign 模式下三个服务的日志里 MQ 相关行数为 **0**，
`t_pay_notify_message` 行数为 **0** —— 开关确实生效，没有连 broker。

## 10.2 ⚠️ 三个服务的值必须一致

`pay` / `order` / `ticket` 都用这个属性。如果只改了 pay，会出现：

```text
pay 投了 PAY_SUCCESS  →  但 order 没在听  →  消息躺在 Broker 里没人消费
```

## 10.3 ★ 一个容易漏的坑：`PayNotifyCompensateJob` 在 mq 模式必须停用

这个任务在 feign 模式下的判据是：

```java
List<PayDO> payList = payMapper.selectList(Wrappers.lambdaQuery(PayDO.class)
        .eq(PayDO::getStatus, PAID)
        .eq(PayDO::getNotifyStatus, NOT_NOTIFIED));      // ← "还没通知下游"
```

**问题在于：`notify_status` 只有 feign 路径的 `notifyPayResult` 会置为 1。**

mq 模式走的是本地消息表，**`t_pay.notify_status` 会永远停在 0**。于是这个任务会把
**每一笔已支付订单**都捞出来，重新走一遍**同步 Feign 通知** ——

> 等于把刚拆掉的同步串行链路又接了回去，
> 既让 mq 模式名不副实，**也让 feign/mq 的 A/B 对比失去意义**。

所以在 mq 模式下直接跳过：

```java
if ("mq".equalsIgnoreCase(notifyMode)) {
    return;
}
```

**mq 模式下"重推未完成的通知"由 `PayNotifyMessageScanJob` 负责**（它扫的是本地消息表）。
两者是**同一角色的两个模式版本**，不该同时工作。

---

# 十一、把这套东西跑起来要知道的三件事

这三条都是**真实踩过的**，不是理论。

## 11.1 ★ topic 必须**预先创建**，不能依赖自动创建

**现象**：所有组件都正常启动了，发送端返回 `SEND_OK`，消息表 `status=10`，
**但消费者一条都没收到。**

原因在 RocketMQ 客户端的日志里：

```text
WARN doRebalance, my12306-pay-notify-order-cg, but the topic[my12306_pay_result] not exist.
```

**因果链**：

```text
消费者启动 → subscribe(topic) → 此时 topic 还不存在
    ↓
拿不到路由 → 不做队列分配
    ↓
生产者第一次发送 → Broker 自动创建 topic
    ↓
但消费者的 rebalance 已经跳过这个 topic，【不会自动恢复】
    ↓
消息一直躺在 Broker 里，没人消费
```

**解决**：启动前显式建 topic：

```bash
mqadmin updateTopic -t my12306_pay_result -c DefaultCluster -r 8 -w 8
```

**顺带一个同源的问题**：死信监视消费者订阅的是 `%DLQ%<消费者组>`，
而这个 topic 只在"第一条死信产生"时才存在 —— 所以它**也会因为同样的原因永远订阅不上**。
**两个 DLQ topic 也必须预先创建。**

> **这条其实是个通用工程结论**：生产环境本来就不该依赖 `autoCreateTopicEnable`，
> 而应该把 topic 的创建纳入部署流程。我在本地环境撞到了它的必然结果。

**实测确认**：预创建之前 `consumerConnection -g <dlq-watcher组>` 查不到任何客户端；
预创建并重启之后，两个 DLQ 监视消费者的客户端数都是 **1**。

## 11.2 客户端为什么用原始 `rocketmq-client`，而不是 starter

```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-client</artifactId>
    <version>5.5.0</version>
</dependency>
```

**不用 `rocketmq-spring-boot-starter` 的原因（实测确认，不是猜测）：**

| 检查 | 结果 |
| --- | --- |
| starter 2.2.3 的自动配置注册方式 | **只有** `META-INF/spring.factories`，**没有** `META-INF/spring/...AutoConfiguration.imports` |
| Spring Boot 3 对此的支持 | **已移除** `spring.factories` 里的 `EnableAutoConfiguration` 机制 |

**后果**：starter 的 `RocketMQAutoConfiguration` 在 Boot 3.3.4 上**根本不会被加载**，
`RocketMQTemplate` 等 Bean **静默不存在** —— 这类问题排查起来很痛苦（没有报错，只是找不到 Bean）。

**所以自己写 `@Configuration` 管生命周期**，好处是与 Spring Boot 版本完全解耦。
**代价**是要自己写 `start()` / `shutdown()`（用 `@Bean(destroyMethod = "shutdown")` 交给容器）。

**版本选择**：broker 是 5.5.1，客户端选 **5.5.0**（只差一个补丁号）。
**实测通过**：`SEND_OK` + 消费收到 + tag 过滤正常。

## 11.3 本机有一个 AF_UNIX 缺陷，所有 JVM 都要带 flag

这台机器**声称支持 AF_UNIX 但实际用不了**，而 JDK 21 会当真。

**症状**（踩过两次）：

```text
io.netty.channel.ChannelException: failed to open a new selector
    at io.netty.channel.nio.NioEventLoop.openSelector
```

Nacos 也起不来，报 `IllegalStateException at AbstractHttpClientFactory.getIoReactor`。

**统一解法**（项目本来就为 JMeter 和 Spring 服务设过，只是 Nacos 漏了）：

```bash
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix"
```

`Z:` 盘不存在 —— **这正是重点**，让 AF_UNIX 不可用从而回退到 TCP。

---

# 十二、验证记录（都是实测，不是设计意图）

## 12.1 两种模式的冒烟

| 模式 | 结果 |
| --- | --- |
| `feign` | 9/9 全绿：订单状态 10、余票 806 → 805 |
| `mq` | 9/9 全绿：订单状态 10、余票 805 → 804 |

## 12.2 链路的完整时序（实测日志）

```text
pay     支付结果事件已投递        paySn=...208
order   收到 PAY_SUCCESS          orderSn=...504   [线程 tify-order-cg_1]
order   已发出 ORDER_PAID         00:11:14.005
ticket  收到 ORDER_PAID           00:11:14.014
```

**顺序正确**：order 发出（.005）早于 ticket 处理（.014），间隔 **9ms**。

## 12.3 逐项对照

| 验证项 | 结果 |
| --- | --- |
| **幂等** | 同一条消息消费 2 次（order/ticket 各收到 2 次），订单状态与车票状态**零变化**，重复座位 0 |
| **本地消息表补发** | 人为把消息改回 `status=0` → 扫描任务日志"兜底扫到 1 条待发送消息" → 重发成功 → `status` 回到 10 |
| **重试** | 毒消息 `reconsumeTimes` 0 → 1，且同批次正常消息不受影响 |
| **顺序** | 见 12.2，order 先于 ticket |
| **开关隔离** | feign 模式：MQ 日志 0 行、消息表 0 行 |
| **死信监视** | **直接往两个 DLQ topic 各发一条消息** → 两个监视消费者都**实际收到了**（日志 `订单侧收到死信！topic=%DLQ%...`）|

> ⚠️ **这一条我返工过一次，很值得记。**
> 最初的验证是"`consumerConnection` 里客户端数各 1"，我就当通过了 ——
> **但这是无效证据**：消费者订阅一个**不存在的 topic 也会注册成功**，只是永远收不到消息。
>
> 真正的问题更隐蔽：我用 `cmd //c "... mqadmin updateTopic -t "%DLQ%..." ..."` 建 DLQ topic，
> **cmd 把 `%DLQ%` 当成了批处理变量并展开成空串**，于是实际建出来的 topic 叫 `my12306-pay-notify-order-cg`，
> **真正的 DLQ topic 一直不存在**。改用 `java` 直调 `MQAdminStartup` 才建对。
>
> **教训**：**"组件启动成功" ≠ "组件真的在工作"**。验证要挑**能证伪**的那个动作 ——
> 对"死信监视能不能收到死信"来说，就是"真发一条进去看它收没收到"。
| **无超卖** | S2 三轮均无重复座位、无超卖 |

## 12.4 ⚠️ 没有验证到的部分（要主动说）

1. **死信不会真的走到**：16 次重试的退避级别最高到 **2 小时**，
   走完 16 次不现实。所以"死信落地 + 监视消费者收到"这一步**只做了结构性验证**
   （重试确实启动、监视消费者确实订阅上了），**没有跑完整耗尽**。
2. **主从复制没做**：Broker 是**单机**，`brokerRole` 保持 `ASYNC_MASTER`。
   `SYNC_MASTER` 的语义是"主等**从**同步复制后才 ACK"，**没有从节点可等，设了没有意义**。
   所以"可靠性三端"的诚实答法是：

   > 生产端 `syncSend` + 判 `SendStatus` ✅
   > Broker **`SYNC_FLUSH` 已保证落盘，但主从复制未部署（单 Broker）** ✅/❌
   > 消费端正常返回才 ACK ✅

   **不要笼统说"可靠性三端都保证了"。**
3. **死信不支持重放**：见 9.3，消费即出队。
4. **"异步化省了多少"已测出来了** —— 见 12.5：**支付回调接口自身 feign ~161ms → mq ~114ms，−29%**。
   但要注意：**如果只看 S3 的端到端流程耗时，结论会完全相反**（mq 慢 129%）——
   那是口径问题，12.5 里讲清了为什么。

---

## 12.5 A/B 压测：**回调自身快 29%，而 S3 的「更慢」是口径假象**

### 12.5.1 ★ 结论先行

**支付回调接口自身的响应时间：feign ~161ms → mq ~114ms，降低约 29%。**

这是唯一能回答"异步化省了多少"的指标 —— 测量方式见下。

### 12.5.2 怎么测的：指标必须对准要证明的事

`S3`（端到端全链路）**不适合回答这个问题**，因为它里面混了客户端等异步结果的轮询。
所以另外写了一个 [`measure-pay-callback.ps1`](../../5-后续开发规划/baseline/measure-pay-callback.ps1)：

```text
每一轮：下单 → 建支付单 → 【只对回调那一次调用计时】→ 不轮询、不等待
```

量到的就是"**支付回调这件事本身花了多久**"：
feign 模式 ≈ 本地事务 + 3 次 Feign（查订单 + 回调 order + 回调 ticket）；mq 模式 ≈ 本地事务 + 1 次消息发送。

### 12.5.3 数字（每臂各两次，各 30 样本，单位 ms）

| 指标 | feign #1 | feign #2 | **mq #1** | **mq #2** | 变化 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 平均 | 157.726 | 164.009 | **112.058** | **115.454** | **−28.9%** |
| P50 | 150.338 | 152.454 | **109.637** | **112.657** | **−26.7%** |
| P95 | 210.545 | 245.042 | **148.985** | **146.286** | **−35.5%** |
| P99 | 223.945 | 300.028 | **166.390** | **154.959** | **−38.2%** |

**为什么这个结论可信（两个判据）**：

1. **两臂区间完全不重叠** —— feign 平均最低 157.7，mq 平均最高 115.5，中间有 42ms 空档。
2. **臂内抖动远小于臂间差异** —— feign 两次差 4.0%、mq 两次差 3.0%，臂间差 28.9%
   ⇒ **臂间差异是臂内抖动的 7 倍以上**。

> **而且 mq 臂是在更高负载下测的** —— 它必须多跑 RocketMQ 两个 JVM。
> 所以 **−29% 是保守值**。

### 12.5.4 为什么只省 29%，没有"两个下游之和"那么多？

因为这次 3 次 Feign 全是**同机 loopback 调用**，单次只有十几毫秒 —— 本地调用本来就便宜。
在真实部署里（跨网络跳转 + 下游各自的处理耗时），把 3 次同步调用换成 1 次异步发送，
省下的会远不止 46ms。**这一点要主动说，否则会被追问"才 29%？"**

### 12.5.5 ⚠️ 反面教材：同一批数据，S3 得出了相反的方向

**同一个批次、同一份代码：S3 说 mq 慢 129%，回调自身说 mq 快 29%。**

| S3 指标 | feign | mq | 变化 |
| --- | ---: | ---: | ---: |
| 平均 (ms) | 1032.3 | **2363.7** | **+129%** |
| P50 (ms) | 762 | **2200** | **+189%** |
| P99 (ms) | 3056 | 4603 | +51% |
| **锁临界区均值 (ms)** | **16.8** | **36.5** | **+117%** |

**为什么 S3 会反过来 —— 两个缺陷：**

**缺陷 ① `S3` 的指标本身惩罚异步：客户端在轮询。**

```java
// s3-full-flow.jmx:74-78
for (int attempt = 0; attempt < 20; attempt++) {
    paid = send('GET', '/api/order-service/order/ticket/query', ...);
    if (paid.data.status == 10) break;
    Thread.sleep(500);          // 每 500ms 才查一次
}
```

- **feign**：`mock-cashier` 返回时订单**已经被同步改成已支付** → 第一次轮询立刻命中，**几乎不等待**
- **mq**：`mock-cashier` 立刻返回，订单**异步**变已支付 → 第一次轮询大概率还是 0，**要等一个或多个 500ms 周期**

⇒ 这段等待被算进了"平均"，而它是**脚本口径**造成的。

**缺陷 ② 两臂负载不同。** mq 臂多跑两个 JVM。
**最有力的旁证是那行锁临界区**：`PurchaseTicketTxService` 里的座位锁在两种模式下**完全相同、与通知无关**，
它也跟着涨了 117% ⇒ 说明机器噪声至少占了一部分。

> **这是整份文档里最值得讲的一课**：
> **指标必须对准你要证明的那件事。**
> 想证明"异步化让支付回调更快"，就得量**支付回调接口自身的耗时**；
> 用"用户端到端流程耗时"去量，会量出相反的结果 —— 因为那个指标里装的是**用户在等**，不是**接口在慢**。

### 12.5.6 顺带一条环境教训

原来的 mq 臂在 S3 第 4 轮预热时**整轮 100/100 失败**，失败点是 `GET /ticket/query`（链路第一个查询）——
查下来是**远程 Redis 主机整机失联**（22/6379/80/3306 全部超时）。

> 整个栈依赖**远程单点 Redis**（缓存 + 网关登录态 + 令牌桶三合一）。
> 它一挂，压测全线失败，而且**失败点看起来像业务错误而不是基础设施故障** ——
> 排查时很容易先怀疑自己刚改的代码。这次是靠"去 ping 那台主机"才把方向拨回来的。
> **另外**：网关鉴权是 **Redis 登录态、不是无状态 JWT**，所以压测用户必须在同一会话内重新生成。

---

# 十三、面试速答

**Q：你这个项目里 MQ 用在哪？**
> 支付成功之后的下游通知。原来是同步串行 Feign（先 order 再 ticket），
> 现在改成链式事件驱动：Pay 发 `PAY_SUCCESS`，order 消费后改状态，再发 `ORDER_PAID`，ticket 消费。
> 可靠性用本地消息表 + 扫描任务兜底。

**Q：为什么不用扇出，要搞成链式？**
> 因为顺序需求是真实的。如果让 ticket 先改了座位（SOLD）而订单还是待支付，
> 超时关单任务可能扫到这笔订单、关单、释放座位 —— 而用户已经付钱了，就超卖了。
> 所以必须先把订单改成已支付。链式就是拿顺序，代价是放弃了一个事件多消费者的扇出形态。

**Q：MQ 怎么保证消息不丢？**
> 我的可靠性其实**不在 MQ 里**。分两层：
> ① 事务内写本地消息表（和支付状态推进同一个事务），所以"已支付"和"要通知"要么都成立要么都不成立；
> ② 提交后尽力发送（`syncSend` 判断 `SendStatus.SEND_OK`，Broker 配了 `SYNC_FLUSH` 所以落盘后才 ACK），
> 发失败就 `retry_count+1` + 退避，由扫描任务补发。
> **中间件只负责传输，可靠性来自本地落状态 + 补偿。**

**Q：消费端消息重复怎么办？**
> 不会出问题，因为消费端用的是**条件更新 + 影响行数**判断，这个能力在做 MQ 之前就有了。
> 比如订单侧 `UPDATE t_order ... WHERE order_sn=? AND status=0`，影响 0 行就说明已经处理过了。
> MQ 只是换了触发源，业务代码一行都没改。

**Q：消费失败了会怎样？**
> 抛异常 → RocketMQ 自动重试（默认 16 次，指数退避）→ 仍失败进死信 `%DLQ%<消费者组>`，
> 我加了一个死信监视消费者做计数和留痕。
>
> **这里有个坑要注意**：RocketMQ 的自动 ACK 是"消费方法正常返回才 ACK"，
> 所以**捕获异常后 return 会被当成消费成功，消息就永久丢了**。我的消费方法刻意不包 try/catch。
>
> 但反过来，有些"重试也不可能变好"的情况**必须 ACK** ——
> 比如订单已关闭后收到支付成功，票务侧没有待推进的车票，这是正常情况；
> 如果抛异常就会变成一条永远重试的毒消息。

**Q：为什么要给消费者留一个 feign 开关？**
> 两个作用：回归保险和 A/B 对照。默认 feign，不改配置行为就跟以前完全一致；
> 而且 feign 模式下 MQ 的 Bean 根本不会创建（`@ConditionalOnProperty`），
> 我把两种模式跑同一套压测脚本对比过。

**Q：这个改造有什么代价？**
> 三个。**① 通知从"同步确认"变成"最终一致"**，扫描任务有最长 10 秒的延迟；
> **② 链式让 ticket 的消费依赖 order 先成功**，放弃了扇出；
> **③ 项目没有共享模块**，所以 topic/tag 常量和消息 DTO 在三个服务里各有一份，改字段要改三处。

**Q：你在这个过程中踩过什么坑？**
> 最值得讲的一个是 **topic 必须预创建**。
> 现象是：所有组件都正常启动、发送端返回 `SEND_OK`、消息表也是已发送，
> **但消费者一条都没收到**。查客户端日志才发现是
> `doRebalance ... but the topic not exist` ——
> 消费者订阅时 topic 还不存在，拿不到路由就不做队列分配，
> 等生产者第一次发送把 topic 自动创建出来之后，**消费者也不会自动恢复**。
>
> 顺带发现死信 topic 有同一个问题（它只在第一条死信出现时才存在），所以两个 DLQ topic 也得预创建。
> **结论是生产环境本来就不该依赖 `autoCreateTopicEnable`，topic 创建要进部署流程。**

**Q：还有一件事 —— 我修正过一份自己写的分析。**
> 早期我把这个问题描述成"远程调用失败会导致事务回滚、把'已支付'状态撤销掉"。
> 后来读代码发现 `notifyPayResult` 里有一个 `catch (Throwable)` 把异常吞掉了，
> **异常根本传不到外层事务，所以不会回滚**。
> 真正的危害只是"连接和行锁被远程调用撑大"。
> **这件事提醒我：讲自己的项目之前一定要回去核对代码，不能凭印象讲。**

---

**Q：改成异步之后，性能变好了吗？**
> 变好了，但**只快 29%，而且这里有个特别值得讲的坑。**
>
> **先说数字**：测**支付回调接口自身**的响应时间，feign ~161ms → mq ~114ms，降了 29%。
> 每臂各测两次、每次 30 样本：两臂区间**完全不重叠**（feign 最低 157.7 > mq 最高 115.5），
> 而臂内抖动只有 3~4% —— 臂间差异是臂内抖动的 7 倍以上。而且 mq 臂是在**更高负载**下测的
> （它必须多跑 RocketMQ 两个 JVM），所以 **29% 是保守值**。
>
> **为什么才 29%？** 因为这次 3 次 Feign 全是**同机 loopback**，单次只有十几毫秒。
> 真实部署里跨网络、下游有真实处理耗时，换成异步发送省下的会远不止这些。
>
> **坑在这里**：如果我用**端到端全链路**（S3）去量，结论会**完全反过来** ——
> S3 显示 mq 慢了 **129%**。为什么？因为 S3 脚本在付款后是**每 500ms 轮询一次**订单状态：
> feign 下订单在回调返回时就已支付，第一次轮询立刻命中；mq 下订单是异步变已支付的，
> 得等一个或多个 500ms 周期。**于是 S3 量到的是"用户在等"，不是"接口在慢"。**
> 旁证是同一批次里**锁临界区均值也涨了 117%** —— 那段代码两臂完全相同、与通知无关，
> 它跟着一起变慢说明机器噪声也占了一部分。
>
> **这件事教我的**：**指标必须对准你要证明的那件事。**
> 要证明"异步化让支付回调更快"，就得量**支付回调接口自身的耗时**。
> 我一开始拿 S3 当指标，差点得出"异步化更差"的错误结论。

**Q：你验证的时候踩过什么坑？**
> 除了前面说的 **topic 必须预创建**，还有一个更值得讲的 —— **我一开始拿了个无效的证据就宣布通过了。**
>
> 死信监视消费者做完之后，我用 `consumerConnection` 看了一下，看到"客户端数各 1"，就认为它工作了。
> **但这证明不了任何事**：消费者订阅一个**不存在的 topic 也会注册成功**，只是永远收不到消息。
>
> 后来才发现真正的问题：我用 `cmd` 建 DLQ topic 时，**cmd 把 `%DLQ%` 当成批处理变量展开成空串**了，
> 于是建出来的 topic 名字少了 `%DLQ%` 前缀，**真正的 DLQ topic 一直不存在**。
> 改用 java 直调管理工具才建对。
>
> **换成"真往 DLQ 里发一条消息、看监视消费者收没收到"之后，一次就验证清楚了。**
>
> **这件事让我总结出两条**：
> ① **"组件启动成功" ≠ "组件真的在工作"**，验证要挑**能证伪**的那个动作；
> ② 说"我验证过了"之前，先问自己一句 **"如果它是坏的，我这个测试会不会失败？"** ——
> 如果不会失败，那它就不是测试，只是仪式。

---

# 附录 A：Broker 侧配置（"可靠性三端"的素材）

| 配置 | 值 | 说明 |
| --- | --- | --- |
| `flushDiskType` | **`SYNC_FLUSH`** | Broker **ACK 前消息已落盘** → 进程崩溃不丢。出厂值是 `ASYNC_FLUSH`，**我改成了同步刷盘** |
| `brokerRole` | `ASYNC_MASTER` | **刻意没改**：`SYNC_MASTER` 是"主等从同步复制后才 ACK"，而本项目**单 Broker、没有从节点**，设了没意义 |
| `brokerName` / `brokerId` | `broker-a` / `0` | `mqadmin getBrokerConfig` 实测 |

**同步刷盘的代价**：写入延迟上升、吞吐下降。
**为什么可以接受**：本项目支付回调的 QPS 很低，这个代价可忽略。

---

# 附录 B：一句话总纲

> **MQ 的本质不是"把 Feign 换成发消息"，而是把"调用关系"改造成"事件关系"。**
>
> 而**可靠性**不是中间件给你的 —— 是你用"本地落状态 + 补偿"自己设计出来的。
