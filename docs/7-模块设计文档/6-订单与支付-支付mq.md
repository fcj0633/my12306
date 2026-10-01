## 结论先说

当前支付链路的本质是：

> 票务服务先占座并创建待支付订单 → 支付服务根据订单创建支付单 → 模拟支付渠道产生支付回调 → 支付服务先在本地确认“钱已到账” → 再把支付结果传播给订单服务和票务服务 → 任一步通知失败都通过重试、补偿和幂等最终收敛。

整个系统没有使用跨库分布式事务。订单库、支付库、票务库分别提交自己的本地事务，通过：

- 状态机；
- 条件更新；
- 幂等；
- 固定处理顺序；
- 定时补偿；
- 可选的本地消息表和 RocketMQ；

实现最终一致性。

当前仓库配置的默认通知模式是 `feign`，也就是支付成功后同步通知订单服务，再同步通知票务服务；如果三个服务同时设置环境变量 `MY12306_PAY_NOTIFY_MODE=mq`，则切换为链式 MQ 模式。仓库中没有发现该环境变量的固定覆盖，因此按当前提交配置直接运行时是 `feign` 模式。[支付服务配置](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/resources/application.yml:52)

还要特别说明：目前接入的不是支付宝、微信，而是 `MOCK_PAY` 模拟渠道。它保留了“创建支付单—跳收银台—渠道异步回调—通知下游”的结构，但没有真实扣款、验签和退款。

---

## 一、整条主链路总览

默认 Feign 模式可以画成：

```text
用户购票
  │
  ▼
Ticket Service
  ├─ 锁定座位：AVAILABLE(0) → LOCKED(1)
  ├─ 创建车票：UNPAID(0)
  └─ 调 Order Service 创建订单
           │
           ▼
Order Service
  ├─ 订单、订单明细：PENDING_PAYMENT(0)
  └─ 提交后投递“20 分钟后关单”的延迟任务
           │
           ▼
用户调用 Pay Service 创建支付单
  ├─ 查询订单、校验归属和状态
  ├─ 服务端汇总订单金额
  ├─ 创建 t_pay：WAIT_PAY(0)
  └─ 返回模拟收银台 payUrl
           │
           ▼
用户访问 payUrl，相当于完成支付
           │
           ▼
Pay Service 接收“渠道回调”
  ├─ 校验 paySn
  ├─ 校验实付金额 = 应付金额
  └─ 本地事务：t_pay WAIT_PAY(0) → PAID(10)
           │
           │ 事务已经提交
           ▼
先通知 Order Service
  └─ 订单和明细：PENDING_PAYMENT(0) → ALREADY_PAID(10)
           │
           ▼
再通知 Ticket Service
  ├─ 车票：UNPAID(0) → PAID(10)
  └─ 座位：LOCKED(1) → SOLD(2)
           │
           ▼
Pay Service
  └─ notify_status：NOT_NOTIFIED(0) → NOTIFIED(1)
```

如果支付成功，但订单或票务通知失败：

```text
t_pay 仍然保持 PAID(10)
notify_status 保持 0
          │
          ▼
PayNotifyCompensateJob 每 60 秒重试
```

也就是说，“钱已经到账”和“下游已经同步完成”是两个不同事实，不能混成一个状态。

---

## 二、必须先理解的四组状态

### 1. 订单状态

订单头 `t_order` 和订单明细 `t_order_item` 都使用：

| 值 | 状态 | 含义 |
|---:|---|---|
| 0 | 待支付 | 订单已经创建，但没有完成付款 |
| 10 | 已支付 | 支付结果已经传播到订单服务 |
| 30 | 已取消 | 主动取消或超时关单 |

只允许：

```text
0 → 10
或
0 → 30
```

不会从 `10` 再回到 `0`，也不会把 `30` 直接改成 `10`。[订单状态推进](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/service/OrderStateService.java:38)

### 2. 支付单状态

`t_pay.status`：

| 值 | 状态 | 含义 |
|---:|---|---|
| 0 | WAIT_PAY | 支付单已创建，等待付款 |
| 10 | PAID | 渠道回调确认支付成功 |
| 30 | CLOSED | 订单已取消，支付单关闭 |

同样只允许：

```text
0 → 10
或
0 → 30
```

表结构还保存：

- `order_sn`：业务订单号；
- `pay_sn`：支付服务生成的支付流水号；
- `total_amount`：应付金额，单位分；
- `pay_amount`：实际支付金额；
- `trade_no`：渠道交易号；
- `gmt_payment`：支付时间；
- `notify_status`：下游通知是否全部完成。

数据库对 `order_sn` 建了唯一索引，所以一笔订单在数据库层只能对应一张支付单。[支付表结构](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/resources/db/12306_pay.sql:6)

### 3. 车票和座位状态

车票：

```text
UNPAID(0) → PAID(10)
UNPAID(0) → CANCELED(30)
```

座位：

```text
AVAILABLE(0) → LOCKED(1) → SOLD(2)
                    │
                    └────→ AVAILABLE(0)   订单取消
```

这里必须区分：

- `LOCKED`：暂时不能再卖，但用户还没完成付款；
- `SOLD`：用户已经完成付款，座位正式售出；
- `AVAILABLE`：可继续出售。

[座位状态定义](/D:/Java-learning/12306Project/12306/my12306/services/ticket-services/src/main/java/edu/swu/fcj/my12306/biz/ticketservice/common/enums/SeatStatusEnum.java:11)  
[车票状态定义](/D:/Java-learning/12306Project/12306/my12306/services/ticket-services/src/main/java/edu/swu/fcj/my12306/biz/ticketservice/common/enums/TicketStatusEnum.java:13)

### 4. 通知状态

Feign 模式下，`t_pay.notify_status` 表示支付结果是否已经完整传播：

| 值 | 含义 |
|---:|---|
| 0 | 至少一个下游没有通知成功 |
| 1 | 订单服务和票务服务都已通知成功 |

这个字段不是支付状态。

可能出现：

```text
status = 10，notify_status = 0
```

它表示：

> 钱已经收到，但订单或座位状态还没有完全同步。

这是正常的短暂中间状态，不能因为通知失败就把支付状态回滚为未支付。[通知状态定义](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/common/enums/PayNotifyStatusEnum.java:6)

---

## 三、支付开始前发生了什么

支付并不是整条购票链路的起点。它的前置条件是票务服务已经完成占座，并且订单服务已经创建了待支付订单。

订单服务创建订单时：

1. `t_order` 写入待支付状态 `0`；
2. 所有 `t_order_item` 写入待支付状态 `0`；
3. 订单事务提交后，向 Redisson 延迟队列投递一个“20 分钟后检查订单”的任务。

之所以是“事务提交后再投延迟任务”，是为了防止：

```text
订单事务最终回滚
但延迟任务已经投递成功
20 分钟后任务尝试关闭一个根本不存在的订单
```

具体实现在：

- [创建待支付订单](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/service/impl/OrderServiceImpl.java:82)
- [事务提交后投递延迟任务](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/mq/OrderDelayCloseProducer.java:35)

这里有一个很重要的思想：

> 座位不是支付成功后才占用，而是下单阶段就先锁住；支付成功只是把“锁定”确认成“出售”。

否则两个用户可能同时支付同一个座位。

---

## 四、创建支付单的实现

入口是：

```http
POST /api/pay-service/pay/create
Authorization: <登录令牌>
Content-Type: application/json

{
  "orderSn": "订单号",
  "channel": "MOCK_PAY",
  "tradeType": "NATIVE"
}
```

接口定义在 [PayController](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/controller/PayController.java:32)。

### 1. 校验登录态

支付服务从 `UserContext` 获取：

- `userId`
- `username`

没有身份信息就拒绝创建支付单。

这个身份不是客户端任意传入的。正常外部请求经过网关后，网关会：

1. 删除客户端可能伪造的用户身份头；
2. 根据 Redis 登录态验证 `Authorization`；
3. 重新注入可信的 `userId`、`username` 和内部令牌。

[网关鉴权逻辑](/D:/Java-learning/12306Project/12306/my12306/services/gateway-services/src/main/java/edu/swu/fcj/my12306/biz/gatewayservice/filter/AuthGlobalFilter.java:24)

### 2. 远程查询订单

支付服务通过 Feign 调用订单服务：

```http
GET /api/order-service/order/ticket/query?orderSn=...
```

然后校验：

- 订单存在；
- 订单属于当前登录用户；
- 订单状态必须是待支付。

如果订单已经支付，返回“无需重复支付”；如果订单已经取消，返回“无法支付”。[创建支付单核心逻辑](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/impl/PayServiceImpl.java:84)

### 3. 金额由服务端计算

创建支付单的请求 DTO 故意没有金额字段。[PayCreateReqDTO](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/dto/req/PayCreateReqDTO.java:5)

支付服务从订单服务拿到每个乘车人的订单明细金额，然后计算：

```java
totalAmount = 每一条订单明细 amount 之和
```

金额单位是“分”。

例如两张票分别为：

```text
53300 分 + 53300 分 = 106600 分
```

这里不能相信前端传来的金额。否则攻击者可以把请求里的 `totalAmount` 从 `106600` 改成 `1`，产生“一分钱支付”的漏洞。

因此金额的可信链是：

```text
订单服务数据库中的订单明细金额
               ↓
支付服务重新汇总
               ↓
t_pay.total_amount
```

### 4. 支付单建单幂等

支付服务先按 `orderSn` 查询已有支付单：

- 已经是 `PAID`：拒绝重复支付；
- 已经是 `CLOSED`：拒绝继续支付；
- 仍是 `WAIT_PAY`：直接复用原支付单和支付链接；
- 不存在：创建新支付单。

新支付单主要字段为：

```text
paySn         = 雪花算法生成
orderSn       = 订单号
channel       = MOCK_PAY
subject       = 车次 + 出发站-到达站
totalAmount   = 服务端计算出的订单总金额
status        = WAIT_PAY(0)
notifyStatus  = NOT_NOTIFIED(0)
```

返回结果包含：

```json
{
  "paySn": "...",
  "orderSn": "...",
  "totalAmount": 106600,
  "payUrl": "http://127.0.0.1:9004/api/pay-service/pay/mock-cashier?paySn=...",
  "status": 0
}
```

### 5. 支付渠道抽象

支付渠道使用工厂加 Handler：

```text
PayChannelFactory
        │
        ├─ MOCK_PAY → MockPayChannelHandler
        ├─ ALIPAY   → 未来可新增
        └─ WXPAY    → 未来可新增
```

Spring 会自动收集所有 `PayChannelHandler` 实现，并根据渠道名选择。[支付渠道工厂](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/channel/PayChannelFactory.java:12)

不过当前抽象只真正覆盖了“构造收银台 URL”；真实渠道的回调参数解析、验签、主动查询、关单、退款等能力还没有进入 Handler 抽象。

---

## 五、模拟收银台是怎么“完成支付”的

当前 `MOCK_PAY` 返回的链接是：

```text
http://127.0.0.1:9004/api/pay-service/pay/mock-cashier?paySn=xxx
```

访问这个 GET 地址，就相当于用户在第三方收银台完成了付款。

模拟收银台会：

1. 根据 `paySn` 查询支付单；
2. 读取支付单自己的 `totalAmount`；
3. 生成一个模拟第三方交易号 `MOCK...`；
4. 记录当前付款时间；
5. 构造 `PayCallbackReqDTO`；
6. 直接调用与真实渠道相同的 `payCallback` 方法。

[模拟收银台实现](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/controller/PayController.java:74)

这意味着虽然“钱”是模拟的，但后面的：

- 回调幂等；
- 支付状态推进；
- 订单状态推进；
- 车票和座位状态推进；
- 通知失败补偿；

都走真实业务代码。

另外，当前仓库没有发现前端模块实际调用 `/pay/create`，所以这里主要实现的是后端 API 契约和可通过接口调用跑通的链路。

---

## 六、支付回调是整条链路的核心

支付回调入口：

```http
POST /api/pay-service/pay/callback
```

请求大致为：

```json
{
  "paySn": "支付流水号",
  "tradeNo": "第三方交易号",
  "payAmount": 106600,
  "channel": "MOCK_PAY",
  "gmtPayment": "2026-09-27T12:00:00"
}
```

入口定义在 [PayController 回调接口](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/controller/PayController.java:56)。

### 回调被拆成了两个阶段

```text
阶段一：事务内
    只修改支付服务自己的数据库

事务提交

阶段二：事务外
    通知订单、票务，或者发送 MQ
```

这是当前实现中最值得理解的设计之一。

---

## 七、为什么要把回调事务拆开

事务内逻辑在 `PayCallbackTxService.markPaid()` 中。[支付回调事务部分](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/impl/PayCallbackTxService.java:59)

它只做以下工作：

1. 查询支付单；
2. 校验应付金额和实付金额；
3. 条件更新支付状态；
4. MQ 模式下，同时写入本地消息表；
5. 提交事务。

它不会在事务里调用订单服务或票务服务。

以前如果写成：

```java
@Transactional
public void payCallback() {
    更新支付单;
    Feign 调订单服务;
    Feign 调票务服务;
}
```

就会产生一个很典型的问题：

```text
支付数据库事务开启
  ↓
锁住 t_pay 行
  ↓
等订单服务网络响应
  ↓
再等票务服务网络响应
  ↓
最后才提交并释放数据库连接、行锁
```

只要下游慢 2 秒，支付数据库连接和行锁就被额外占用 2 秒。高并发下很容易造成：

- 连接池耗尽；
- 锁等待增加；
- 回调吞吐下降；
- 下游雪崩反向拖垮支付服务。

当前代码通过独立 Bean 保证事务代理生效：

```text
PayServiceImpl.payCallback()
          │
          ▼
PayCallbackTxService.markPaid()  ← @Transactional
          │
          └─ 方法返回时事务已经提交
          │
          ▼
再进行 Feign 调用或 MQ 发送
```

这是不能随意合并回一个类里的。尤其不能重新给 `PayServiceImpl.payCallback()` 加上大范围 `@Transactional`。

---

## 八、支付状态是怎样幂等推进的

核心 SQL 语义是：

```sql
UPDATE t_pay
SET status = 10,
    pay_amount = ?,
    trade_no = ?,
    gmt_payment = ?
WHERE pay_sn = ?
  AND status = 0;
```

也就是：

> 只有仍处于待支付状态的支付单，才能被改成已支付。

代码条件在 [PayCallbackTxService](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/impl/PayCallbackTxService.java:94)。

这比“先查状态、再更新”更安全。

错误写法：

```text
请求 A 查询：status = 0
请求 B 查询：status = 0
请求 A 更新为 10
请求 B 也更新为 10，并重复执行后续逻辑
```

当前写法把判断放入数据库 `WHERE`：

```text
请求 A：UPDATE ... WHERE status = 0，影响 1 行
请求 B：UPDATE ... WHERE status = 0，影响 0 行
```

判断和修改成为一次原子操作。

### 重复回调的处理

第三方支付平台重复推送回调是正常情况，例如：

- 第一次回调成功，但 HTTP 响应丢失；
- 渠道没有收到成功响应，于是再次推送；
- 网络超时导致多次投递。

如果支付单已经是 `PAID`，当前代码直接返回成功，不重复更新支付事实。

但它仍会继续尝试通知下游：

- Feign 模式：如果 `notify_status=0`，可顺便再次通知；
- MQ 模式：如果本地消息还没发成功，可再次触发发送。

所以重复回调不只是“不会破坏数据”，还可能帮助系统更快自愈。

---

## 九、金额校验为什么非常关键

支付回调时，会严格比较：

```java
payDO.totalAmount.equals(request.payAmount)
```

不相等直接拒绝。[回调金额校验](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/impl/PayCallbackTxService.java:75)

这里验证的是：

```text
创建支付单时由服务端计算出的应付金额
                    ==
渠道通知的实际支付金额
```

例如：

```text
应付：106600 分
回调：1 分
```

即使回调声称支付成功，也不会把支付单改为已支付。

不过必须注意：当前只有金额校验，没有真实渠道验签。所以这只是模拟环境的防篡改手段，不能替代真实支付平台的 RSA/HMAC 签名验证。

---

## 十、默认 Feign 通知链路

支付事务提交后，`PayServiceImpl.payCallback()` 根据配置选择通知模式。[回调后的模式分流](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/impl/PayServiceImpl.java:156)

默认 `feign` 模式调用：

```java
notifyPayResult(paySn)
```

通知顺序严格是：

```text
1. 查询订单详情
2. 通知订单服务
3. 通知票务服务
4. 两者都成功后，notify_status = 1
```

[Feign 通知实现](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/impl/PayServiceImpl.java:218)

### 第一步：为什么还要重新查询订单详情

支付服务需要订单详情来构造票务回调参数，包括：

- `trainId`
- 出发站
- 到达站
- 车厢号
- 座号
- 席别

这些数据决定票务服务具体要把哪些座位从锁定改成已售。

### 第二步：先通知订单服务

调用：

```http
POST /api/order-service/order/ticket/pay-callback
```

订单服务把：

```text
t_order：0 → 10
t_order_item：0 → 10
```

订单头和订单明细在同一个本地事务里推进。[订单支付回调](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/service/impl/OrderServiceImpl.java:236)

订单状态同样使用条件更新：

```sql
WHERE order_sn = ?
  AND status = 0
```

重复回调影响 0 行，不会重复修改。

### 第三步：再通知票务服务

调用：

```http
POST /api/ticket-service/ticket/pay-callback
```

对每个座位执行：

```text
先抢车票状态：
t_ticket UNPAID(0) → PAID(10)

抢到以后再改座位：
t_seat LOCKED(1) → SOLD(2)
```

[票务支付回调](/D:/Java-learning/12306Project/12306/my12306/services/ticket-services/src/main/java/edu/swu/fcj/my12306/biz/ticketservice/service/impl/TicketCallbackServiceImpl.java:58)

这里“先改车票，再改座位”非常重要。

`t_seat` 只靠车次、区间、席别、车厢、座号定位，它没有直接表达“这个锁属于哪个订单”。而 `t_ticket` 带有 `orderSn`。

因此先通过：

```sql
UPDATE t_ticket
SET ticket_status = 10
WHERE order_sn = ?
  AND ...
  AND ticket_status = 0;
```

获得“当前订单对此座位的处理权”。

只有影响 1 行的请求才允许继续改座位。重复回调、取消后到达的旧回调都会因为车票已不再是 `UNPAID` 而跳过。

如果车票改成已支付后，座位却没能从 `LOCKED` 改为 `SOLD`，代码会在同一事务中把车票恢复为 `UNPAID`，避免出现：

```text
车票显示已支付
但座位没有卖出去
```

最后会删除余票缓存，让下次查询重新从数据库计算。

---

## 十一、为什么必须“订单先于票务”

这是整条支付链路中最核心的顺序约束。

安全顺序是：

```text
支付单已支付
    ↓
订单已支付
    ↓
车票已支付、座位已售
```

为什么不能让订单和票务并行处理？

假设票务先完成：

```text
座位已售
订单仍是待支付
```

这时超时关单任务可能看到订单仍为待支付，于是：

1. 把订单关掉；
2. 把支付单关闭；
3. 回滚座位；
4. 座位重新变成可售。

但用户其实已经付钱，座位又被释放给其他用户，可能导致超卖。

而当前顺序下，如果订单先变成已支付，即使票务通知暂时失败：

```text
订单：已支付
座位：仍锁定
```

也相对安全：

- 超时任务看到订单已支付，不会关单；
- `LOCKED` 和 `SOLD` 对其他用户而言都不可售；
- 后续补偿只需要把 `LOCKED` 补成 `SOLD`。

这体现了一条非常实用的设计原则：

> 出现短暂不一致时，要选择“不会超卖、可以补偿”的中间状态。

---

## 十二、Feign 通知失败怎么补偿

如果以下任一步失败：

- 查询订单失败；
- 订单服务调用失败；
- 票务服务调用失败；

支付服务不会回滚 `t_pay.status=PAID`。

它会：

```text
status = 10
notify_status = 0
```

然后 `PayNotifyCompensateJob` 每 60 秒扫描：

```sql
SELECT *
FROM t_pay
WHERE status = 10
  AND notify_status = 0;
```

对每一笔重新执行 `notifyPayResult(paySn)`。[Feign 补偿任务](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/job/PayNotifyCompensateJob.java:50)

例如第一次通知：

```text
订单服务成功
票务服务失败
```

第二次重试时：

- 订单服务发现订单已经支付，直接返回成功；
- 票务服务继续尝试推进车票和座位；
- 全部成功后，支付服务把 `notify_status` 改成 `1`。

所以补偿任务允许“从头重放”，而不是精确记录执行到了第几步。之所以能这么做，是因为每个下游操作都是幂等的。

多实例部署时，该补偿任务通过 Redis/Redisson 分布式锁保证每一轮只有一个支付服务实例扫描，减少重复 Feign 调用。

不过数据库表目前没有 `(status, notify_status)` 组合索引，数据量变大后这段扫描会逐渐变贵；当前任务也没有分页或 `LIMIT`，这是后续扩容时需要处理的问题。

---

## 十三、可选的 MQ 支付通知模式

如果三个服务统一配置：

```text
MY12306_PAY_NOTIFY_MODE=mq
```

链路变成：

```text
Pay Service
    │
    │ PAY_SUCCESS
    ▼
Order Service
    │
    │ ORDER_PAID
    ▼
Ticket Service
```

注意不是：

```text
                ┌─ Order
Pay ─ PAY_SUCCESS
                └─ Ticket
```

也就是说，票务服务不直接订阅 `PAY_SUCCESS`，而是等待订单服务成功处理后产生的 `ORDER_PAID`。

这是链式事件驱动，不是并行扇出。

### 1. 支付状态与本地消息同事务

MQ 模式下，`PayCallbackTxService.markPaid()` 在同一个数据库事务里完成：

```text
UPDATE t_pay：WAIT_PAY → PAID
INSERT t_pay_notify_message：PENDING
```

两者要么一起提交，要么一起回滚。[本地消息写入](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/impl/PayCallbackTxService.java:114)

这解决了一个经典问题：

```text
场景 A：
支付状态已经提交
进程在发 MQ 前崩溃
→ 没有消息，下游永远不知道

场景 B：
先发 MQ
MQ 消费者已经处理
支付数据库事务却回滚
→ 下游认为已支付，但支付服务认为没支付
```

本地消息表让系统变成：

```text
支付状态和“待发送消息”一定同时存在
```

表上还有：

```sql
UNIQUE(pay_sn, event_type)
```

防止同一笔支付产生两条相同业务事件。[本地消息表结构](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/resources/db/12306_pay_alter_p1.sql:39)

### 2. 事务提交后立即发送

提交后，`PayNotifyMessageSender` 按 `paySn` 查出本地消息，同步发到 RocketMQ：

```text
topic = my12306_pay_result
tag   = PAY_SUCCESS
key   = paySn
```

RocketMQ 返回 `SEND_OK` 后：

```text
t_pay_notify_message.status：0 → 10
```

[MQ 消息发送器](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/mq/PayNotifyMessageSender.java:57)

### 3. 发送失败后的重试

如果 broker 不可用、网络异常，消息保持 `PENDING(0)`。

`PayNotifyMessageScanJob` 默认每 10 秒扫描：

```sql
WHERE status = 0
  AND retry_count < 10
  AND next_retry_time <= NOW()
ORDER BY id
LIMIT 100
```

重试使用指数退避，最长 60 秒。超过 10 次不再自动重发，暴露指标并打印需要人工处理的错误。[本地消息扫描任务](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/job/PayNotifyMessageScanJob.java:82)

因此：

```text
立即发送负责低延迟
本地消息表扫描负责可靠性
```

### 4. 订单消费 PAY_SUCCESS

订单服务收到 `PAY_SUCCESS` 后：

1. 调用与 Feign 入口相同的 `payCallbackOrder()`；
2. 把订单和明细从 `0` 推进到 `10`；
3. 再发送一条 `ORDER_PAID`。

[订单 MQ 消费者](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/mq/PayResultOrderConsumer.java:60)

如果发送 `ORDER_PAID` 失败，消费者抛异常，不返回 ACK。RocketMQ 会重新投递 `PAY_SUCCESS`。

重新消费时：

- 订单状态已经是已支付，订单推进成为空操作；
- 再次尝试发送 `ORDER_PAID`。

如果 `ORDER_PAID` 已发送成功，但消费者在 ACK 前崩溃，也可能再次发送 `ORDER_PAID`。所以 MQ 链路提供的是“至少一次”而不是“严格只一次”。

### 5. 票务消费 ORDER_PAID

票务服务收到 `ORDER_PAID` 后：

1. 根据 `orderSn` 查询自己库中的车票；
2. 只筛选仍处于 `UNPAID` 的车票；
3. 组装座位坐标；
4. 复用 `TicketCallbackService.payCallback()`；
5. 把车票改为已支付、座位改为已售。

[票务 MQ 消费者](/D:/Java-learning/12306Project/12306/my12306/services/ticket-services/src/main/java/edu/swu/fcj/my12306/biz/ticketservice/mq/PayResultTicketConsumer.java:53)

如果所有车票已经不是未支付，例如：

- 已经处理过；
- 订单关闭后车票已取消；

消费者直接 ACK，不会让这种正常情况变成一条永远重试的“毒消息”。

### 6. MQ 模式下不用 `notify_status`

MQ 模式下，`t_pay.notify_status` 会一直保持 `0`。

此时真正的通知进度记录在：

```text
t_pay_notify_message.status
```

因此 Feign 补偿任务在 MQ 模式下会直接退出，避免：

```text
MQ 已经在通知
Feign 补偿任务又把所有支付结果同步通知一遍
```

这是两个互斥模式：

| 模式 | 可靠性依据 |
|---|---|
| Feign | `t_pay.notify_status` + `PayNotifyCompensateJob` |
| MQ | `t_pay_notify_message.status` + `PayNotifyMessageScanJob` + MQ 重试 |

---

## 十四、超时未支付和主动取消链路

### 1. 两套关单触发机制

下单成功后，订单服务有两种触发方式：

- Redisson 延迟队列：负责大约 20 分钟到点触发；
- 定时扫表：默认每 60 秒扫描一次，负责补漏。

定时任务查询：

```sql
WHERE status = 0
  AND order_time < 当前时间 - 20分钟
```

[超时关单兜底任务](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/job/OrderTimeoutCloseJob.java:20)

用户主动取消也复用同一个 `closeTimeoutOrder()` 业务方法，只多做登录和订单归属校验。

### 2. 关单顺序

当前顺序为：

```text
1. 先关闭订单和订单明细
2. 再通知支付服务关闭支付单
3. 最后通知票务服务释放座位
```

本地订单事务：

```text
t_order：0 → 30
t_order_item：0 → 30
```

事务提交后调用支付服务：

```text
t_pay：0 → 30
```

再调用票务服务：

```text
t_ticket：0 → 30
t_seat：LOCKED(1) → AVAILABLE(0)
```

释放座位后，Redis 令牌桶中的对应席别令牌在数据库事务提交后归还。

[关单业务实现](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/service/impl/OrderServiceImpl.java:265)  
[票务取消回调](/D:/Java-learning/12306Project/12306/my12306/services/ticket-services/src/main/java/edu/swu/fcj/my12306/biz/ticketservice/service/impl/TicketCallbackServiceImpl.java:83)

### 3. 为什么必须先关订单再释放座位

错误顺序：

```text
先释放座位
    ↓
订单状态还没来得及关闭
    ↓
用户此时完成支付
```

就可能出现：

```text
订单支付成功
但原座位已经重新卖给别人
```

因此必须先把订单从可支付状态 `0` 原子地改成不可支付状态 `30`，然后才能释放座位。

---

## 十五、幂等是怎么分层实现的

当前链路不是只在一个地方做幂等，而是层层防御：

| 层次 | 幂等机制 |
|---|---|
| 创建订单 | `order_sn` 唯一约束；相同订单号复用 |
| 创建支付单 | 先按 `order_sn` 查询；`uk_order_sn` 数据库唯一约束 |
| 支付回调 | `UPDATE ... WHERE status=WAIT_PAY` |
| MQ 本地消息 | `UNIQUE(pay_sn, event_type)` |
| 订单支付回调 | `UPDATE ... WHERE status=PENDING_PAYMENT` |
| 车票支付回调 | `UPDATE ... WHERE ticket_status=UNPAID` |
| 座位支付回调 | `UPDATE ... WHERE seat_status=LOCKED` |
| 关支付单 | `UPDATE ... WHERE status=WAIT_PAY` |
| 取消车票 | `UPDATE ... WHERE ticket_status=UNPAID` |
| 释放座位 | `UPDATE ... WHERE seat_status=LOCKED` |

所以系统接受：

- 渠道重复回调；
- Feign 补偿重复通知；
- MQ 重复投递；
- 延迟关单重复消费；
- 定时任务重复扫描。

核心不是让重复绝对不发生，而是：

> 允许重复发生，但重复执行不会产生第二次业务效果。

---

## 十六、当前实现最需要警惕的边界和缺口

下面这些不是理论问题，而是阅读当前代码后需要明确知道的实际边界。

### 1. 不是生产级真实支付接入

当前缺少：

- 支付宝/微信 SDK；
- 回调验签；
- 商户号、应用 ID 校验；
- 第三方订单号唯一性校验；
- 主动查单；
- 渠道侧关单；
- 退款；
- 对账；
- 金额、币种、商户订单号等完整校验。

而且网关明确把下面两个接口设为公开：

```text
GET  /api/pay-service/pay/mock-cashier
POST /api/pay-service/pay/callback
```

[支付公开路由](/D:/Java-learning/12306Project/12306/my12306/services/gateway-services/src/main/java/edu/swu/fcj/my12306/biz/gatewayservice/filter/AuthGlobalFilter.java:35)

这对模拟环境是为了方便跑通链路；生产环境绝不能照搬。当前任何知道 `paySn` 的人都可能访问模拟收银台触发“支付成功”。

### 2. 超时关单与支付成功之间仍有跨服务竞态

虽然单库内部都使用条件更新，但订单库、支付库、票务库之间没有全局事务。

尤其 `closeTimeoutOrder()` 当前逻辑是：

```java
if (最初查到订单是待支付) {
    boolean closed = closePendingOrder(orderSn);
}
无论 closed 是否为 true：
    关闭支付单;
    回滚座位;
```

这会把两种 `closed=false` 混在一起：

```text
情况 A：订单早已关闭，当前只是补偿重试
情况 B：刚刚被支付回调抢先推进为已支付
```

对于情况 A，继续补齐下游是正确的；对于情况 B，继续关闭支付和释放座位是不安全的。

可能出现：

```text
关单线程先查到订单待支付
                 │
支付线程把订单 0 → 10
                 │
关单线程 UPDATE 0 → 30 影响 0 行
                 │
但关单线程仍继续调用票务取消回调
                 │
订单已支付，车票却可能被取消、座位被释放
```

相反，也可能是订单关单先赢，支付服务已经把 `t_pay` 改成 `PAID`，最终出现：

```text
支付单已支付
订单已关闭
车票已取消
```

当前订单服务对此只记录错误日志，并明确注明“真实场景需要退款”，但退款尚未实现。[关闭订单后收到支付成功](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/service/impl/OrderServiceImpl.java:245)

因此当前链路能通过幂等避免大量重复破坏，但尚未完全解决“支付成功与超时关单同时发生”的跨服务竞争。

更稳妥的处理应该是：

- `closePendingOrder()` 返回 `false` 后重新查询最终订单状态；
- 如果最终是 `ALREADY_PAID`，必须停止关闭支付单和释放座位；
- 如果渠道确实已经收款但订单已关闭，进入自动退款或人工补偿流程；
- 支付回调与关单需要建立更明确的业务时间和渠道订单状态判定。

### 3. 支付单已关闭后的回调被当作“处理成功”

`PayCallbackTxService` 中，如果条件更新影响 0 行，可能是：

- 另一个回调线程已经处理；
- 支付单已经关闭。

当前两种情况都返回 `true`。[状态推进未生效分支](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/impl/PayCallbackTxService.java:103)

这意味着“支付单已关闭后收到真实渠道成功通知”时：

```text
t_pay 仍是 CLOSED
没有记录本次收款
却向渠道返回了成功语义
```

在模拟环境里问题不明显；真实支付环境必须区分：

- 重复成功回调；
- 已关闭后发生真实扣款；
- 渠道订单已关闭；
- 需要退款或人工介入。

### 4. 模拟收银台返回文案有轻微语义误导

`payCallback()` 当前返回值表达的是：

```text
支付是否已经入账
```

而不是：

```text
所有下游是否通知成功
```

但模拟收银台把变量命名为 `notified`，返回文案是“下游通知结果”。如果下游调用失败，`payCallback()` 仍可能返回 `true`，因为支付已经成功入账，只是后续等待补偿。

因此看接口响应时不要误解：支付回调 HTTP 成功只代表支付事实被接受，不代表订单、车票、座位已经全部同步完毕。

### 5. 并发创建支付单可能返回唯一键异常

当前建支付单是：

```text
先查 orderSn 是否已有支付单
再 INSERT
```

数据库有 `UNIQUE(order_sn)`，所以不会真的产生两张支付单。但两个并发请求可能同时查到不存在，然后：

```text
请求 A INSERT 成功
请求 B INSERT 撞唯一键
```

请求 B 目前没有像订单创建那样捕获 `DuplicateKeyException` 后重新查询复用，因此可能返回数据库异常，而不是优雅地返回原支付单。

所以当前做到的是“数据库数据不重复”，还没有完全做到“并发接口响应也幂等”。

### 6. 支付查询接口没有业务层归属校验

`getPayInfoByOrderSn()` 和 `getPayInfoByPaySn()` 会要求请求经过网关登录鉴权，但支付服务内部查询方法没有再次校验这张支付单是否属于当前用户。

因此如果一个已登录用户知道别人的 `orderSn` 或 `paySn`，理论上可能查询到别人的支付信息。生产实现应增加：

```text
pay.user_id == 当前登录 userId
```

或者把查询条件直接写成：

```sql
WHERE pay_sn = ?
  AND user_id = ?
```

### 7. 当前没有已支付订单的退票退款链路

代码明确只支持：

```text
待支付 → 已支付
待支付 → 已取消
```

不支持：

- 已支付订单退票；
- 部分退款；
- 全额退款；
- 支付单退款状态；
- 退款单；
- 渠道退款回调。

所以用户主动取消已支付订单会直接得到“请使用退票”，但项目实际上还没有完成退票退款闭环。

---

## 十七、最值得记住的设计思想

如果只抓重点，建议记住下面七点。

1. **订单号和支付流水号不是一个东西。**

   `orderSn` 由购票/订单链路产生；`paySn` 由支付服务创建支付单时产生。一笔订单目前只能有一张支付单。

2. **金额必须以服务端订单明细为准。**

   创建支付单不接收前端金额；回调时再校验实付金额是否与应付金额完全一致。

3. **支付成功是事实，下游同步是传播过程。**

   `status=PAID` 不能因为订单或票务服务暂时不可用而回滚；传播失败必须补偿。

4. **先订单，后票务。**

   订单先变成已支付，可以阻止超时关单；座位暂时保持锁定也不会被别人买走。这是安全的中间状态。

5. **事务只包本地数据库，不包远程调用。**

   支付状态先在短事务中提交，再进行 Feign 或 MQ 通知，避免数据库锁和连接横跨网络请求。

6. **系统依靠“至少一次 + 幂等”，不是追求消息绝不重复。**

   条件更新和唯一约束让重复回调、重复消息、重复补偿都成为安全的空操作。

7. **MQ 本身不是可靠性的全部。**

   MQ 模式真正的可靠性来自：

   ```text
   支付状态 + 本地消息同事务落库
   +
   扫描未发送消息重试
   +
   消费端幂等
   ```

   RocketMQ 主要承担异步传输和削峰。

---

## 十八、按源码阅读的推荐顺序

如果你要继续深入这条链路，建议按下面顺序阅读：

1. [支付接口入口 PayController](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/controller/PayController.java:32)
2. [创建支付单与回调分流 PayServiceImpl](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/impl/PayServiceImpl.java:84)
3. [支付回调本地事务 PayCallbackTxService](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/service/impl/PayCallbackTxService.java:59)
4. [订单支付与关单 OrderServiceImpl](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/service/impl/OrderServiceImpl.java:211)
5. [订单原子状态推进 OrderStateService](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/service/OrderStateService.java:38)
6. [票务支付/取消回调 TicketCallbackServiceImpl](/D:/Java-learning/12306Project/12306/my12306/services/ticket-services/src/main/java/edu/swu/fcj/my12306/biz/ticketservice/service/impl/TicketCallbackServiceImpl.java:58)
7. [Feign 模式补偿 PayNotifyCompensateJob](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/job/PayNotifyCompensateJob.java:50)
8. [MQ 本地消息发送 PayNotifyMessageSender](/D:/Java-learning/12306Project/12306/my12306/services/pay-services/src/main/java/edu/swu/fcj/my12306/biz/payservice/mq/PayNotifyMessageSender.java:57)
9. [MQ 订单消费者 PayResultOrderConsumer](/D:/Java-learning/12306Project/12306/my12306/services/order-services/src/main/java/edu/swu/fcj/my12306/biz/orderservice/mq/PayResultOrderConsumer.java:60)
10. [MQ 票务消费者 PayResultTicketConsumer](/D:/Java-learning/12306Project/12306/my12306/services/ticket-services/src/main/java/edu/swu/fcj/my12306/biz/ticketservice/mq/PayResultTicketConsumer.java:53)

本次结论基于当前仓库源码和配置的静态分析，没有修改项目文件。