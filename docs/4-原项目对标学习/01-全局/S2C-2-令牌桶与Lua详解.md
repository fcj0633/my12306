# S2C-2 详解：令牌桶与 Lua —— 在进入锁和事务之前先拦住注定失败的请求

> 本篇是《[项目整体结构](./项目整体结构.md)》第 2 层"购票链路 S2C"中 **S2C-2** 一步的展开。
> 总览文档只给结论，本篇给完整推导过程：为什么这么设计 → 数据结构长什么样 → Java 与 Lua 怎么配合 → 什么时候回滚 → 桶偏了怎么自愈。

---

## 0. 读这篇之前

**前置依赖**（这些不懂，后面会卡住）：

| 前置 | 在哪学 | 为什么需要 |
| --- | --- | --- |
| S2C-1 购票责任链 | 总览文档 S2C-1 | 令牌桶排在责任链**之后**，要知道前面挡了什么 |
| Redis Hash 结构 | Redis 基础 | 令牌桶就是一个 Hash，Field 是"出发站_到达站_席别" |
| Redis 单线程执行模型 | Redis 基础 | "为什么必须用 Lua"的答案就在这 |
| Redisson 可重入锁 / 公平锁 | 组件库 | 惰性初始化用到了 `tryLock` |
| Spring `StringRedisTemplate.execute(RedisScript, keys, args...)` | Spring Data Redis | Lua 从 Java 侧怎么发起调用 |

**代码坐标**（原项目根目录 `datasource/originalProject/12306/`）：

| 角色 | 文件 | 规模 |
| --- | --- | --- |
| Java 门面（5 个 public 方法，其中 `putTokenInBucket` / `initializeTokens` 是空实现） | `T:services/ticket-service/.../service/handler/ticket/tokenbucket/TicketAvailabilityTokenBucket.java` | 204 行 |
| Lua · 取令牌 | `res:services/ticket-service/src/main/resources/lua/ticket_availability_token_bucket.lua` | 46 行 |
| Lua · 回滚令牌 | `res:services/ticket-service/src/main/resources/lua/ticket_availability_rollback_token_bucket.lua` | 27 行 |
| 主调用方 | `T:services/ticket-service/.../service/impl/TicketServiceImpl.java` L398–456（取令牌）、L653–683（自愈） | — |
| 回滚触发点 | `TicketServiceImpl.cancelTicketOrder` L563 / `mq/consumer/DelayCloseOrderConsumer` L125 / `canal/OrderCloseCacheAndTokenUpdateHandler` L69 | — |
| 区间计算 | `T:.../toolkit/StationCalculateUtil.java` L40、L66 | 94 行 |
| Key 常量 | `T:.../common/constant/RedisKeyConstant.java` L114、L139、L144 | — |

> 自研工程 `12306/my12306` 的对应位置在 `PurchaseTicketServiceImpl`，其类注释写明"简化点：不做余量令牌桶"。本篇只讲原项目。

---

## 1. 先把问题量化：没有它会怎样

场景：某趟高铁，**北京南 → 南京南 二等座余票 10 张**，开售瞬间涌进 **5000 个并发购票请求**，每个请求买 1 张。

按 `TicketServiceImpl.purchaseTicketsV2`（L398 起）的执行顺序，如果没有令牌桶：

| 步骤 | 5000 个请求会怎样 |
| --- | --- |
| ① 责任链（参数非空、可售时间、余票缓存校验） | 这一瞬间缓存里都还是"有票"，**5000 个全部通过**。真正能成交的只有 10 个请求，但余票缓存的扣减是滞后的 |
| ② 抢锁（车次+席别的 Redisson **公平锁**） | 5000 个请求在 Redis 上排队。公平锁保证顺序，但也意味着后 4990 个会一直挂着等 |
| ③ 进事务 `executePurchaseTickets` | 每个拿到锁的请求都要打开 `@Transactional`（L459），在里面查 `t_seat`、`INSERT t_ticket`、再 Feign 调订单服务 |
| ④ 发现没座，抛异常回滚 | 4990 个请求各做了一次"完整的失败事务"：开事务、查库、跨服务调用、回滚 |

**核心矛盾**：这 4990 个请求的失败在①之后就已经是**确定的**了，但我们花了②③④三份代价才发现。

令牌桶要做的只有一件事：**在②③之前，用一个 Redis 里已有的整数，把这 4990 个请求砍掉。**

判断"这趟车这个区间还剩多少可卖"，不需要查数据库，只需要在 Redis 里读一个数——这是整个设计成立的前提。

---

## 2. 设计思想

### 2.1 它到底是什么：库存的一份"粗糙副本"

`TicketAvailabilityTokenBucket` 的类注释写的是"列车车票余量令牌桶，应对海量并发场景下满足并行、限流以及防超卖等场景"。这句话里的"防超卖"容易被误读。准确的定位是：

> **令牌桶是 `t_seat` 表可用座位数的一份快照副本，加上之后每一次购票/回滚的增量。它的精度要求很低。**

- **令牌 ≠ 座位**。令牌是"允许进入后续购票流程的许可证数量"。
- **多放几个进来没关系**：后面的双层锁 + 座位分配 + 占座事务会兜住，用户最多白跑一趟。
- **少放了才是问题**：明明有票却告诉用户"已无余票"，这是直接把生意拒掉。

所以设计上取的是"**宁可稍宽松 + 事后自愈**"：允许漂移，但用第 8 节的 10 秒复查机制把"偏少"这一侧拉回来。

### 2.2 它不是什么：不保证不超卖

这是初学最容易搞混的一点。整个购票链路是**三层职责分离**的：

| 层 | 组件 | 职责 | 能保证什么 | 不能保证什么 |
| --- | --- | --- | --- | --- |
| 第 1 层 | **令牌桶**（本篇，S2C-2） | 廉价准入 | 挡掉"一定失败"的流量，保护后面的锁与数据库 | 不保证不超卖 |
| 第 2 层 | 双层锁（S2C-3） | 串行化 | 同一车次+席别的并发修改不会交错 | 不保证库存算得对 |
| 第 3 层 | 座位分配 + 占座事务（S2C-4/S2C-5） | 最终正确性 | 不超卖、失败可回滚 | 性能差，不能单独扛并发 |

所以"令牌桶防超卖"这句话的正确理解是：**它防的是"因超卖而产生的无效流量"，不是超卖本身。** 即使令牌桶完全失效，第 2、3 层也能保证不出错，只是数据库要被打穿。

### 2.3 和经典"令牌桶限流器"的区别

名字沿用了"令牌桶"，但和 Guava `RateLimiter`、Sentinel 那种令牌桶是两回事：

| 维度 | 经典令牌桶限流器 | 本项目令牌桶 |
| --- | --- | --- |
| 令牌怎么补充 | 按时间匀速补充（如 100/s） | **不补充**。初始化时一次性写入，靠订单关闭/取消回滚补回 |
| 桶的数量 | 一个接口一个桶 | **一趟车 = 一个 Hash，里面 N 个区间 × M 个席别都是独立桶** |
| 令牌总数 | 固定容量，与业务无关 | = 该区间该席别的可售座位数快照，**是业务量** |
| 拒绝语义 | 拒绝即"请求太频繁" | 拒绝即"这个席别这个区间没票了" |
| 与业务的关系 | 完全解耦 | **强耦合**，桶的内容就是库存 |

一句话：**它是"分段式库存计数器"，不是"速率限制器"**。真正"限流"的那部分（同一用户不能重复提交下单请求）是 `purchaseTicketsV2` 上的 `@Idempotent` 注解在做的——它的 SPEL key 是 `unique-name + '_' + username`（`TicketServiceImpl.java` L391–393），按用户维度拦截，不是它。

### 2.4 为什么必须用 Lua

设想把"检查"和"扣减"拆成两条普通 Redis 命令：

```text
初始：北京南_南京南_2 = 1（只剩最后一张）

请求 A: HGET     桶 北京南_南京南_2   → 1   （1 >= 1，够，准备扣）
请求 B: HGET     桶 北京南_南京南_2   → 1   （1 >= 1，够，准备扣）
请求 A: HINCRBY  桶 北京南_南京南_2  -1  → 0
请求 B: HINCRBY  桶 北京南_南京南_2  -1  → -1   ← 令牌被超发
```

两个请求都通过了检查，最后扣成 **-1**。检查与扣减之间必须是一个不可分割的整体。

| 方案 | 能否满足 | 问题 |
| --- | --- | --- |
| 两条普通命令 | ✗ | 上图，超扣 |
| `MULTI/EXEC` 事务 | ✗ | Redis 事务**不支持在中间根据执行结果做分支**。"检查"的结果要回传给客户端才能决定扣不扣，而一旦回传，事务就已经结束了 |
| `WATCH` + 乐观锁 | △ | 能做条件判断，但被 WATCH 的 Key 一旦被改动，`EXEC` 会返回 nil，并发一高就是大量重试 |
| 外部分布式锁包住 | △ | 为了"检查+扣减"再加一把锁，锁竞争回到原点，性能不升反降；而且锁粒度、超时、续期都是额外负担 |
| **Lua 脚本** | ✓ | 脚本在 Redis 里是**单线程整体执行**的，脚本执行期间不会插入任何其他客户端的命令。同时只需要 1 次 RTT |

**结论**：Lua 不是因为"语法方便"才用的，而是因为它提供了**服务端原子执行 + 条件分支**这两个能力，而这两点恰好是"检查后扣减"的刚需。这是这道题的标准答案，讲的时候要说清楚"拆开会超扣"，而不是只说"Lua 是原子的"。

### 2.5 为什么桶是惰性初始化的

反着想：如果启动时把全国所有车次的桶都建好——

一趟车有 N 个站，就有 `N × (N-1) / 2` 个区间；高铁有 3 种席别。一趟 5 站的车就是 10 区间 × 3 席别 = **30 个 Hash Field**。乘以全国车次数、再乘以 15 天预售期（`ADVANCE_TICKET_DAY = 15`，`Index12306Constant.java` L29），是天文数字。而其中 99% 的车次当天根本没人买。

所以改成**惰性初始化**（`TicketAvailabilityTokenBucket.java` L100–123）：

- 第一次有人抢某趟车的票时，才为这趟车建桶；
- 用 `redissonClient.getLock(...)` + **双重检查**（`hasKey` 判两次）保证只建一次；
- 代价是**第一个请求会慢**——它要遍历这趟车所有区间，每个区间打一次 `listSeatTypeCount` SQL（见 4.1.2）。

这个代价是可以接受的：它只在"这趟车当前预售期内第一次有人买票"时发生一次，之后所有请求都只走 Lua。

---

## 3. 数据结构：Key / Field / Value 到底是什么

### 3.1 三层结构

`TicketAvailabilityTokenBucket.java` L99 定义了 Key，L114 定义了 Field：

| 层 | 内容 | 具体例子 |
| --- | --- | --- |
| **Redis Key** | `TICKET_AVAILABILITY_TOKEN_BUCKET + trainId` | `index12306-ticket-service:ticket_availability_token_bucket:1` |
| **Hash Field** | `出发站_到达站_席别` | `北京南_南京南_2` |
| **Value** | 该"区间 × 席别"的剩余可售数量 | `"10"` |

Key 常量在 `RedisKeyConstant.java` L114：

```java
public static final String TICKET_AVAILABILITY_TOKEN_BUCKET = "index12306-ticket-service:ticket_availability_token_bucket:";
```

**为什么用 Hash 而不是一堆 String？** 两个理由，都能在代码里指出来：

1. **整趟车的令牌在一个 Key 里**，一次 `DEL` 就能把整趟车重置——`delTokenInBucket`（L191–195）就是这么做的，代码只有一行 `stringRedisTemplate.delete(tokenBucketHashKey)`。
2. **单个 Field 支持 `HINCRBY` 原子增减**——Lua 第二阶段（L42）正是靠它完成扣减。

### 3.2 必须分清的三份"余票"

这是本篇最容易混的地方。同一个购票链路里同时存在**三份余票**，它们的载体、写入方、精度都不同：

| 名字 | 载体 | 谁写 | 精度 |
| --- | --- | --- | --- |
| **真实库存** | MySQL `t_seat` 表（`seat_status = '0'`） | 占座 `lockSeat` / 解锁 `unlock`（S2C-4） | 精确，最终真相 |
| **站点余票缓存** | Redis Hash `TRAIN_STATION_REMAINING_TICKET + trainId_出发_到达`，Field = 席别 | 购票成功时扣、订单关闭时回滚 | 近似 |
| **令牌桶** | Redis Hash `TICKET_AVAILABILITY_TOKEN_BUCKET + trainId`，Field = `出发_到达_席别` | 取令牌时扣、回滚令牌时加 | 更近似（快照 + 增量） |

对照关系：

- S2C-1 的 20 号闸门 `TrainPurchaseTicketParamStockChainHandler` 读的是**第二份**（站点余票缓存）；
- S2C-2 的令牌桶是**第三份**，它在第二份之后又加了一道；
- 三份数据由不同的代码路径维护，所以**天然会漂移**。第 8 节的 10 秒自愈机制就是为了处理这种漂移。

判断"读哪一份"的简单口诀：**越靠前的闸门读越廉价的副本，越靠后的环节读越精确的真相。**

---

## 4. 核心代码走查（Java 侧）

### 4.1 `takeTokenFromBucket`：一个方法干四件事

`TicketAvailabilityTokenBucket.java` L89–149，方法签名：

```java
public TokenResultDTO takeTokenFromBucket(PurchaseTicketReqDTO requestParam)
```

返回 `TokenResultDTO`（`service/handler/ticket/dto/TokenResultDTO.java`），只有两个字段：

```java
private Boolean tokenIsNull;                        // 有没有拿到令牌
private List<String> tokenIsNullSeatTypeCounts;     // 没拿到的席别_数量，如 ["2_2"]
```

方法体按职责切成四段。

#### 4.1.1 第一段：拿到车次信息和全趟区间（L90–97）

```java
TrainDO trainDO = distributedCache.safeGet(
        TRAIN_INFO + requestParam.getTrainId(), TrainDO.class,
        () -> trainMapper.selectById(requestParam.getTrainId()),
        ADVANCE_TICKET_DAY, TimeUnit.DAYS);
List<RouteDTO> routeDTOList = trainStationService
        .listTrainStationRoute(requestParam.getTrainId(), trainDO.getStartStation(), trainDO.getEndStation());
```

- `trainDO` 是为了拿 `trainDO.getTrainType()`（车型），后面要靠它推出这趟车有哪些席别。
- `routeDTOList` 是**整趟车的全部区间**（首站到末站之间的所有 i<j 组合）。**注意它只在初始化时用到**，取令牌的过程本身不用它。

`listTrainStationRoute` 的实现（`TrainStationServiceImpl.java` L54–61）很直接：查出这趟车所有 `departure` 组成的列表，交给 `StationCalculateUtil.throughStation` 做两两组合。

#### 4.1.2 第二段：惰性初始化（L100–123）—— 这段是全篇最值得精读的

```java
String tokenBucketHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
Boolean hasKey = distributedCache.hasKey(tokenBucketHashKey);
if (!hasKey) {                                                                          // ① 快路径
    RLock lock = redissonClient.getLock(String.format(LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET, requestParam.getTrainId()));
    if (!lock.tryLock()) {                                                              // ② 抢不到直接失败
        throw new ServiceException("购票异常，请稍候再试");
    }
    try {
        Boolean hasKeyTwo = distributedCache.hasKey(tokenBucketHashKey);                // ③ 双重检查
        if (!hasKeyTwo) {
            List<Integer> seatTypes = VehicleTypeEnum.findSeatTypesByCode(trainDO.getTrainType());
            Map<String, String> ticketAvailabilityTokenMap = new HashMap<>();
            for (RouteDTO each : routeDTOList) {                                        // ④ 遍历全部区间
                List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(
                        Long.parseLong(requestParam.getTrainId()), each.getStartStation(), each.getEndStation(), seatTypes);
                for (SeatTypeCountDTO eachSeatTypeCountDTO : seatTypeCountDTOList) {
                    String buildCacheKey = StrUtil.join("_", each.getStartStation(), each.getEndStation(), eachSeatTypeCountDTO.getSeatType());
                    ticketAvailabilityTokenMap.put(buildCacheKey, String.valueOf(eachSeatTypeCountDTO.getSeatCount()));
                }
            }
            stringRedisTemplate.opsForHash().putAll(TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId(), ticketAvailabilityTokenMap);
        }                                                                               // ⑤ 一次性写入
    } finally {
        lock.unlock();
    }
}
```

逐点拆解：

- **① 快路径 `hasKey` 在锁外**。99.99% 的请求走到这里发现 Key 已存在，直接跳过整个 `if` 块，**完全不碰分布式锁**。这是性能关键：不能为了初始化而让所有请求都去抢锁。
- **② `tryLock()` 失败就抛异常**，不是 `lock.lock()` 阻塞等待。理由：如果改用阻塞等待，5000 个并发请求就会全部堆积在初始化锁上；而初始化本身只需要几十毫秒，让少量请求直接失败、由用户重试更划算。这也是为什么错误信息是"购票异常，请稍候再试"而不是"无余票"——它是一个**可重试**的错误。
- **③ 双重检查**（Double-Checked Locking）。第一个 `hasKey` 是真快路径检查，第二个 `hasKeyTwo` 是拿到锁之后的权威检查。没有它，两个实例会先后建桶，后一次 `putAll` 会覆盖前一次的扣减结果。
- **④ 初始化的循环**：外层遍历**区间**，内层遍历**席别**。`VehicleTypeEnum.findSeatTypesByCode`（`VehicleTypeEnum.java` L97–103）按车型返回席别集合：

  | 车型 | code | 席别 |
  | --- | --- | --- |
  | 高铁 `HIGH_SPEED_RAIN` | 0 | 商务座(0)、一等座(1)、二等座(2) |
  | 动车 `BULLET` | 1 | 二等包座(3)、一等卧(4)、二等卧(5)、无座(13) |
  | 普通车 `REGULAR_TRAIN` | 2 | 软卧(6)、硬卧(7)、硬座(8)、无座(13) |

- **④ 续：这里的 SQL 代价**。内层调用 `seatService.listSeatTypeCount`，最终落到 `mapper/SeatMapper.xml` L38–51：

  ```sql
  select seat_type as seatType, count(*) as seatCount
  from t_seat
  where train_id = #{trainId} and start_station = #{startStation} and end_station = #{endStation}
    and seat_status = '0'
    and seat_type in (...)
  group by seat_type
  having seatCount > 0
  ```

  注意 `having seatCount > 0`：**余票为 0 的席别根本不会出现在结果里，也就不会被写进桶**。这个细节很重要，它和第 5 节 Lua 阶段一的 `hget` 读不到值直接相关，务必记住。

- **⑤ `putAll` 是"批量写入"**。整个 Map 一次性写进 Hash，比逐个 `put` 少 N-1 次 RTT。

> **阅读注意点**：`throughStation`（`StationCalculateUtil.java` L44）在 `startIndex < 0 || endIndex < 0 || startIndex >= endIndex` 时会**静默返回空列表**。如果 `t_train_station` 里查出来的出发站序列和 `trainDO` 的首末站对不上（例如末站只作为 `arrival` 出现、不在 `departure` 列表里），`routeDTOList` 就是空的，桶会被建成一个**空 Hash**，表现为"所有请求都提示无余票"。排查令牌桶异常时，这是第一嫌疑点。

#### 4.1.3 第三段：把购票请求翻译成 Lua 的两个参数（L124–143）

```java
DefaultRedisScript<String> actual = Singleton.get(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH, () -> {
    DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
    redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH)));
    redisScript.setResultType(String.class);
    return redisScript;
});
Assert.notNull(actual);
Map<Integer, Long> seatTypeCountMap = requestParam.getPassengers().stream()
        .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType, Collectors.counting()));
JSONArray seatTypeCountArray = seatTypeCountMap.entrySet().stream()
        .map(entry -> { /* {"seatType": "2", "count": "3"} */ })
        .collect(Collectors.toCollection(JSONArray::new));
List<RouteDTO> takeoutRouteDTOList = trainStationService
        .listTakeoutTrainStationRoute(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());
```

这里做了三件容易被忽略的事：

**(1) `Singleton` 让 Lua 脚本只在 JVM 里加载一次**

`Singleton`（`frameworks/base/.../bases/Singleton.java` L31–54）就是一个 `ConcurrentHashMap` 缓存：

```java
private static final ConcurrentHashMap<String, Object> SINGLE_OBJECT_POOL = new ConcurrentHashMap();

public static <T> T get(String key, Supplier<T> supplier) {
    Object result = SINGLE_OBJECT_POOL.get(key);
    if (result == null && (result = supplier.get()) != null) {
        SINGLE_OBJECT_POOL.put(key, result);
    }
    return result != null ? (T) result : null;
}
```

没有它，每个请求都要用 `ClassPathResource` 去读一次 classpath 文件。有了它，脚本内容全进程只从磁盘读一次。

**(2) `groupingBy(seatType, counting())` 把乘车人列表压成"席别 → 张数"**

用户买了"张三二等座 + 李四二等座 + 王五二等座"，压缩后是 `{2: 3}` → `[{"seatType":"2","count":"3"}]`。
**为什么要压缩**：Lua 阶段一只需要知道"二等座要 3 张"，不需要知道是哪三个人。压缩让 Lua 的循环次数从"乘车人数"降到"席别数"。

**(3) 两个 Key 的分工**

`stringRedisTemplate.execute(actual, Lists.newArrayList(tokenBucketHashKey, luaScriptKey), ARGV1, ARGV2)`：

| 位置 | 值 | Lua 里的名字 | 用途 |
| --- | --- | --- | --- |
| KEYS[1] | `index12306-ticket-service:ticket_availability_token_bucket:1` | `KEYS[1]` | 真正被 `hget` / `hincrby` 操作的 Hash |
| KEYS[2] | `北京南_南京南` | `KEYS[2]` → 剥前缀后 → `actualKey` | **只是一个字符串前缀**，用来拼 Field |
| ARGV[1] | `[{"seatType":"2","count":"3"}]` | `jsonArray` | 要买哪些席别、各几张 |
| ARGV[2] | `[{"startStation":...,"endStation":...}, ...]` | `alongJsonArray` | 这次购票会影响到的区间段 |

**这里有个必须解释的疑问**：KEYS[2] 既然是常量字符串，为什么不直接传 `ARGV`？因为 **Redis 集群模式下，只有 KEYS 里的 key 会参与 hash slot 计算**。放到 KEYS 里能保证被路由到和 KEYS[1] 同一个节点。而 `ARGV` 里的 JSON 数组含逗号、花括号，不可能是合法 key 名，所以只能放 ARGV。

**(4) `listTakeoutTrainStationRoute` 算的是"占用区间"，不是"乘车区间"**

这是本段最反直觉的一点。`StationCalculateUtil.takeoutStation`（L66–86）：

```java
public static List<RouteDTO> takeoutStation(List<String> stations, String startStation, String endStation) {
    List<RouteDTO> takeoutStationList = new ArrayList<>();
    int startIndex = stations.indexOf(startStation);
    int endIndex = stations.indexOf(endStation);
    if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
        return takeoutStationList;
    }
    if (startIndex != 0) {
        for (int i = 0; i < startIndex; i++) {
            for (int j = 1; j < stations.size() - startIndex; j++) {
                takeoutStationList.add(new RouteDTO(stations.get(i), stations.get(startIndex + j)));
            }
        }
    }
    for (int i = startIndex; i <= endIndex; i++) {
        for (int j = i + 1; j < stations.size() && i < endIndex; j++) {
            takeoutStationList.add(new RouteDTO(stations.get(i), stations.get(j)));
        }
    }
    return takeoutStationList;
}
```

举例说明（沿用原作者在 `StationCalculateUtil.main` 里写的例子）：
站点序列 `[北京南, 济南西, 南京南, 杭州东, 宁波]`，买 **北京南 → 南京南**。

- `startIndex = 0`，`endIndex = 2`；
- 第一个 `if (startIndex != 0)` 不成立（北京南是始发站），跳过；
- 第二个双层循环，`i` 从 0 到 2，但内层有 `i < endIndex` 的条件：

| i | j 取值 | 产生的区间 |
| --- | --- | --- |
| 0 | 1,2,3,4 | 北京南_济南西、北京南_南京南、北京南_杭州东、北京南_宁波 |
| 1 | 2,3,4 | 济南西_南京南、济南西_杭州东、济南西_宁波 |
| 2 | 因 `i < endIndex` 为假，不产生 | — |

得到 **7 个区间**，而乘车区间只有 1 个（北京南_南京南）。

**为什么要扣这么多？** 因为座位是**复用**的：一个"北京南_宁波"的座位，中间被人买走"北京南_南京南"这段，两段是同一个物理座位，必须一起减。这就是 12306 的核心业务约束——**一趟车的座位在区间上是可复用的，卖出一段会挤占所有跨过这段的长区间**。

对照一下另一个方法 `throughStation`（L40–56），它算的是**初始化时要建桶的全部区间**——用的是车次自己的首末站，穷举所有 i<j，得到的是"这张票可能被在任意哪一段卖掉"的全集。两个方法的用途完全不同，不要混。

#### 4.1.4 第四段：执行并解析结果（L144–148）

```java
String resultStr = stringRedisTemplate.execute(actual, Lists.newArrayList(tokenBucketHashKey, luaScriptKey), JSON.toJSONString(seatTypeCountArray), JSON.toJSONString(takeoutRouteDTOList));
TokenResultDTO result = JSON.parseObject(resultStr, TokenResultDTO.class);
return result == null
        ? TokenResultDTO.builder().tokenIsNull(Boolean.TRUE).build()
        : result;
```

- `stringRedisTemplate.execute(RedisScript, keys, args...)` 内部由 Spring 的 `DefaultScriptExecutor` 处理：**先尝试 `EVALSHA`**（只说脚本的 SHA1，省带宽），收到 `NOSCRIPT` 错误时**自动回退到 `EVAL`**（把整段脚本发过去），之后 Redis 就记住了这个 SHA1。
- **最后两行的兜底方向值得注意**：如果解析出来是 `null`（比如脚本异常返回空），代码把它当成 `tokenIsNull = TRUE`，也就是**当成"没票"处理**。这是一个明确的取舍：令牌桶是准入闸门，异常时选择"拒绝"（fail-closed）而不是"放行"。放行会让请求涌向数据库，拒绝了最多是这一次购票失败。

---

## 5. 核心代码走查（Lua 1：取令牌）

文件：`services/ticket-service/src/main/resources/lua/ticket_availability_token_bucket.lua`

### 5.1 全文

```lua
local inputString = KEYS[2]                                    -- ①
local actualKey = inputString
local colonIndex = string.find(actualKey, ":")
if colonIndex ~= nil then
    actualKey = string.sub(actualKey, colonIndex + 1)
end

local jsonArrayStr = ARGV[1]
local jsonArray = cjson.decode(jsonArrayStr)                   -- ②

local result = {}
local tokenIsNull = false
local tokenIsNullSeatTypeCounts = {}

-- ── 阶段一：只检查，不扣减 ──
for index, jsonObj in ipairs(jsonArray) do
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    local actualInnerHashKey = actualKey .. "_" .. seatType    -- ③
    local ticketSeatAvailabilityTokenValue = tonumber(redis.call('hget', KEYS[1], tostring(actualInnerHashKey)))  -- ④
    if ticketSeatAvailabilityTokenValue < count then           -- ⑤
        tokenIsNull = true
        table.insert(tokenIsNullSeatTypeCounts, seatType .. "_" .. count)
    end
end

result['tokenIsNull'] = tokenIsNull
if tokenIsNull then
    result['tokenIsNullSeatTypeCounts'] = tokenIsNullSeatTypeCounts
    return cjson.encode(result)                                -- ⑥
end

-- ── 阶段二：全部充足，才批量扣减 ──
local alongJsonArrayStr = ARGV[2]
local alongJsonArray = cjson.decode(alongJsonArrayStr)         -- ⑦

for index, jsonObj in ipairs(jsonArray) do
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    for indexTwo, alongJsonObj in ipairs(alongJsonArray) do
        local startStation = tostring(alongJsonObj.startStation)
        local endStation = tostring(alongJsonObj.endStation)
        local actualInnerHashKey = startStation .. "_" .. endStation .. "_" .. seatType
        redis.call('hincrby', KEYS[1], tostring(actualInnerHashKey), -count)   -- ⑧
    end
end

return cjson.encode(result)
```

### 5.2 逐段解释

**① 剥前缀（L1–6）**

```lua
local inputString = KEYS[2]
local actualKey = inputString
local colonIndex = string.find(actualKey, ":")
if colonIndex ~= nil then
    actualKey = string.sub(actualKey, colonIndex + 1)
end
```

KEYS[2] 传进来的是 `"北京南_南京南"`。这里找**第一个冒号**并截掉它及之前的部分。

为什么需要这段？因为框架的 `DistributedCache` 支持配置 `framework.cache.redis.prefix` 给所有 Redis Key 加统一前缀（`TicketServiceImpl.java` L162–163 的 `cacheRedisPrefix` 就是同一个东西）。如果配了前缀，`KEYS[2]` 可能长成 `"myapp:北京南_南京南"`，不剥掉就拼不出正确的 Field。**这是一个防御性代码**：默认配置下 `string.find` 返回 `nil`，整段是空操作。

**② 解码参数（L8–9）**

`ARGV[1]` 是 Java 侧 `JSON.toJSONString(seatTypeCountArray)` 的产物，形如 `[{"seatType":"2","count":"3"}]`。`cjson.decode` 把它还原成 Lua 表。

注意字段值都是**字符串** `"2"`、`"3"`（Java 侧 `jsonObject.put("seatType", String.valueOf(...))` 显式转成了 String），所以下面必须用 `tonumber()` 转回来。

**③ 拼 Field（L18）**

```lua
local actualInnerHashKey = actualKey .. "_" .. seatType
```

`actualKey` 是"出发_到达"，拼上席别，得到 `北京南_南京南_2`——和 Java 侧 L114 `StrUtil.join("_", ...)` 的拼法必须**完全一致**。这两处是隐式契约，任何一边改了分隔符，脚本就会静默读不到值。

**④ 读当前令牌数（L19）**

```lua
local ticketSeatAvailabilityTokenValue = tonumber(redis.call('hget', KEYS[1], tostring(actualInnerHashKey)))
```

- `tostring()` 是因为 Lua 里 `seatType` 已经是 number（被 `tonumber` 转过），拼进 key 名要显式转字符串；
- **`hget` 在 Field 不存在时返回 `false`（Lua 里的 false，不是 nil）**。`tonumber(false)` 返回 `nil`。于是下一行 `nil < count` 在 Lua 里是**运行时错误**（`attempt to compare nil with number`），整个脚本会失败抛错。

  这不是理论问题：4.1.2 的初始化 SQL 有 `having seatCount > 0`，**余票为 0 的席别根本不会写进桶**。所以"初始化时该席别已售完"的情况下，取令牌会走到这个分支。

  理解这一点的意义不在于"这是个 bug"，而在于：**它告诉这个脚本对 Field 缺失没有任何兜底**，一旦桶的内容和请求的 Field 拼法对不上，表现是"购票接口报错"而不是"提示无余票"。排查时这两者的区别很重要。

**⑤ 记录不足的席别（L20–23）**

```lua
if ticketSeatAvailabilityTokenValue < count then
    tokenIsNull = true
    table.insert(tokenIsNullSeatTypeCounts, seatType .. "_" .. count)
end
```

记录的是 `"席别_请求张数"`，例如 `"2_3"` 表示"二等座、想要 3 张"。这个信息不是给用户看的，是给第 8 节的自愈机制用的——它需要知道"用户想要多少"，才能去数据库比对"真实余票够不够"。

**⑥ 关键设计：有一个不够，就一行都不扣（L26–30）**

```lua
result['tokenIsNull'] = tokenIsNull
if tokenIsNull then
    result['tokenIsNullSeatTypeCounts'] = tokenIsNullSeatTypeCounts
    return cjson.encode(result)
end
```

`return` 在 `ipairs` 循环之外，所以是**先从全部席别检查完、再决定**，不是边查边扣。

假设改成"边检查边扣"：用户买 1 张二等座 + 1 张一等座，二等座够、一等座不够。走到一等座才发现不够时，**二等座的令牌已经扣掉了**。这时要么：
- 把二等座加回去 → 需要写补偿逻辑，补偿本身又要保证原子（万一补偿失败呢）；
- 不加回去 → 用户这次没买成，但令牌永久少了一个，库存被凭空吞掉。

**提前 return 让脚本只有两种终止状态：全部扣减成功，或一行都没动。** 这就是"要么全拿到，要么一个都不拿"的原子语义，它把补偿问题从根上消掉了。

这个设计也解释了为什么 `TokenResultDTO` 要带 `tokenIsNullSeatTypeCounts` —— 失败时要精确告诉上层"是哪几个席别不够、各差多少"。

**⑦ 第二阶段的数据来源（L32–33）**

`ARGV[2]` 是 4.1.3 算出的 7 个区间（以北京南→南京南为例）。注意第一阶段**完全没用它**——第一阶段的 Field 只到"席别"粒度（`actualKey .. "_" .. seatType`），因为"这个区间够不够"取决于用户实际要坐的那一段；而第二阶段要扣的是**所有会受影响的区间**。

**⑧ 批量扣减（L35–44）**

```lua
for index, jsonObj in ipairs(jsonArray) do        -- 外层：席别
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    for indexTwo, alongJsonObj in ipairs(alongJsonArray) do   -- 内层：区间
        ...
        redis.call('hincrby', KEYS[1], tostring(actualInnerHashKey), -count)
    end
end
```

- 复杂度是 `席别数 × 区间数`。以高铁为例最多 3 × 7 = 21 次 `HINCRBY`，**全部在 Redis 内部一次往返完成**。
- 用 `hincrby` 而不是 `hset` 是必须的：`hset` 是覆盖写，而**并发的其他请求可能正在对同一个 Field 做扣减**，覆盖会丢更新。`hincrby` 是原子读改写。
- 注意这里**没有检查扣减后是否变成负数**。因为在同一个原子脚本里，阶段一刚刚检查过 ≥ count，理论上不会扣成负。真正变成负的唯一途径是"多个 Field 初始值不一致 + 并发"这类异常，那时由第 8 节的自愈机制兜。

### 5.3 取令牌的完整语义

| 返回 | 含义 | 上层动作 |
| --- | --- | --- |
| `{"tokenIsNull": false}` | 令牌已扣，可以继续 | 进入双层锁 + 事务（S2C-3/5） |
| `{"tokenIsNull": true, "tokenIsNullSeatTypeCounts": ["2_3"]}` | 二等座不足，**且一个令牌都没扣** | 抛 `ServiceException("列车站点已无余票")`，并触发 10 秒后自愈复查 |

---

## 6. 触发点：`purchaseTicketsV2` 里怎么用取令牌的结果

`TicketServiceImpl.java` L398–416：

```java
@Override
public TicketPurchaseRespDTO purchaseTicketsV2(PurchaseTicketReqDTO requestParam) {
    // 责任链模式，验证 1：参数必填 2：参数正确性 3：乘客是否已买当前车次等...
    purchaseTicketAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);   // ①
    TokenResultDTO tokenResult = ticketAvailabilityTokenBucket.takeTokenFromBucket(requestParam);                          // ②
    if (tokenResult.getTokenIsNull()) {
        Object ifPresentObj = tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId());                              // ③
        if (ifPresentObj == null) {
            synchronized (TicketService.class) {                                                                          // ④
                if (tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId()) == null) {                              // ⑤
                    ifPresentObj = new Object();
                    tokenTicketsRefreshMap.put(requestParam.getTrainId(), ifPresentObj);
                    tokenIsNullRefreshToken(requestParam, tokenResult);                                                    // ⑥
                }
            }
        }
        throw new ServiceException("列车站点已无余票");
    }
    // ... 下面才是双层锁 + executePurchaseTickets（S2C-3 / S2C-5）
}
```

### 6.1 顺序的意义：为什么令牌桶排在责任链之后、锁之前

| 顺序 | 组件 | 成本 | 挡住什么 |
| --- | --- | --- | --- |
| ① | 责任链（S2C-1） | 极低，纯内存/一次缓存读 | 参数为空、不在可售时间、**站点余票缓存已为 0** |
| ② | **令牌桶** | 低，1 次 Redis EVAL | 余票缓存还没更新、但令牌已经扣完的请求 |
| ③ | 双层锁（S2C-3） | 高，Redis 排队 | 并发修改 |
| ④ | 事务（S2C-5） | 最高，DB 连接 + Feign | — |

**每往下一层，成本就高一个量级。** 令牌桶放在②而不是③，就是在"最便宜的、还能做出准确判断的地方"把请求劝返。这也是这套准入设计的一般原则：**按成本从低到高排列闸门，让绝大多数请求在廉价的闸门处终止。**

### 6.2 ③④⑤ 的三层防重：为什么需要这么麻烦

令牌不足时会执行 `tokenIsNullRefreshToken`。如果不加防护，**4990 个失败的请求每个都会去 schedule 一个 10 秒延迟任务 + 查一次数据库**——一个本该"省资源"的机制，反而变成数千次查库的放大器。

三层防重对应三种不同的失效场景：

| 层 | 机制 | 防的是什么 |
| --- | --- | --- |
| ③ | Caffeine `tokenTicketsRefreshMap.getIfPresent`（`expireAfterWrite(10, MINUTES)`，类字段 L384–386） | 同实例内、10 分钟内的重复触发。这是**快路径**，99% 的请求在这里返回 |
| ④ | `synchronized (TicketService.class)` | ③ 和 ⑤ 之间的竞态窗口：多个线程可能同时通过③ |
| ⑤ | 第二次 `getIfPresent` | 拿到锁之后必须重新检查（经典双重检查），否则排队进来的线程会把任务重复放进 map |

**注意 Caffeine 的过期时间（10 分钟）刚好比延迟任务的 10 秒长得多**，这是有意的：10 秒后复查完了，这个"标记"还留着，防止同一车次在短时间内被反复复查。

---

## 7. 回滚：令牌怎么还回去

令牌被扣掉之后，如果订单最终没成交，必须还回去。否则桶会只减不增，越用越少。

### 7.1 三个触发点

`rollbackInBucket`（`TicketAvailabilityTokenBucket.java` L156–184）在项目里被三个地方调用：

| 触发点 | 位置 | 场景 |
| --- | --- | --- |
| 用户主动取消订单 | `TicketServiceImpl.cancelTicketOrder` L563 | 用户点了"取消订单" |
| 延迟消息关单 | `DelayCloseOrderConsumer` L125（MQ 延迟消息消费） | 下单后 30 分钟未支付，订单被关闭 |
| Canal 订阅关单 | `OrderCloseCacheAndTokenUpdateHandler` L69 | 监听 `t_order` 表 binlog，`status = 30` 的行出现时触发 |

**三个触发点不会重复执行**，因为有配置开关 `ticket.availability.cache-update.type`：

- 取值为 `binlog` 时，前两条路径整体跳过（`TicketServiceImpl.java` L550、`DelayCloseOrderConsumer.java` L96 都是 `&& !StrUtil.equals(ticketAvailabilityCacheUpdateType, "binlog")`）；这时只有 Canal 路径生效。
- 取值不是 `binlog`（默认空串）时，走前两条路径，Canal 的 `CanalCommonSyncBinlogConsumer` 在 L69 判定、L70 直接 `return`。

这是"同一件事的两套实现，用配置二选一"的典型写法，读代码时看到三个调用点别慌，先找这个开关。

### 7.2 回滚 Lua（Lua 2）

文件：`lua/ticket_availability_rollback_token_bucket.lua`，只有 13 行有效逻辑：

```lua
local inputString = KEYS[2]
local actualKey = inputString
local colonIndex = string.find(actualKey, ":")
if colonIndex ~= nil then
    actualKey = string.sub(actualKey, colonIndex + 1)
end

local jsonArrayStr = ARGV[1]
local jsonArray = cjson.decode(jsonArrayStr)
local alongJsonArrayStr = ARGV[2]
local alongJsonArray = cjson.decode(alongJsonArrayStr)

for index, jsonObj in ipairs(jsonArray) do
    local seatType = tonumber(jsonObj.seatType)
    local count = tonumber(jsonObj.count)
    for indexTwo, alongJsonObj in ipairs(alongJsonArray) do
        local startStation = tostring(alongJsonObj.startStation)
        local endStation = tostring(alongJsonObj.endStation)
        local actualInnerHashKey = startStation .. "_" .. endStation .. "_" .. seatType
        local ticketSeatAvailabilityTokenValue = tonumber(redis.call('hget', KEYS[1], tostring(actualInnerHashKey)))
        if ticketSeatAvailabilityTokenValue >= 0 then                      -- ★
            redis.call('hincrby', KEYS[1], tostring(actualInnerHashKey), count)
        end
    end
end

return 0
```

和 Lua 1 对比，三处差异值得注意：

**差异一：没有"阶段一 / 阶段二"之分。** 回滚是"能加就加"，不需要"全部成功或全部失败"的语义，所以直接加。

**差异二：加了 `>= 0` 的守卫（★ 标记处）。** 这是唯一的判断。

为什么需要它？举一个真实会发生的场景：

1. 订单 A 扣了令牌，`北京南_南京南_2` 从 10 变成 8；
2. 因为某种原因（比如 8.2 节的删桶重建）桶被 `DEL` 了，重新初始化时 `北京南_南京南_2` 又变成了 10；
3. 现在订单 A 关闭，回滚把它加成 12。

**12 已经超过真实座位数了。** 令牌被凭空放大，闸门形同虚设。

`>= 0` 这个判断能拦住一部分这种情况（如果当前值是 -1 说明确实被超额扣了，不加），但**它拦不住上面这个例子**——因为重建后的值是 10，`10 >= 0` 成立，照样会加。

**所以这条守卫是"尽力而为"级别的防护，不是精确的幂等控制。** 真正要精确，需要在桶里额外记录"每个订单扣了多少"，成本高得多。这里的设计选择是：**接受一定程度的令牌虚高**，因为虚高的后果只是"多放几个请求进来，最后被锁和事务挡掉"，而不是超卖。这正好呼应了 2.2 节的职责边界。

**差异三：返回值约定。** 脚本固定 `return 0`。Java 侧（L180–183）校验：

```java
if (result == null || !Objects.equals(result, 0L)) {
    log.error("回滚列车余票令牌失败，订单信息：{}", JSON.toJSONString(requestParam));
    throw new ServiceException("回滚列车余票令牌失败");
}
```

返回值不是 0 就抛异常 + 打 error 日志。这是一个**可观测性设计**：脚本本身不可能返回非 0，所以一旦出现，说明调用链路上有别的问题（比如 key 传错、脚本被替换），必须告警而不是静默吞掉。

---

## 8. 自愈：桶偏了怎么办

### 8.1 先看漂移的方向

令牌桶会漂移，但两个方向的后果不对称：

| 漂移方向 | 后果 | 严重程度 |
| --- | --- | --- |
| **令牌偏多**（桶里的数 > 真实余票） | 放进来的人比座位多 → 在双层锁和占座事务里失败，用户白跑一趟 | 可接受，第 2、3 层兜底 |
| **令牌偏少**（桶里的数 < 真实余票） | 明明有票，用户被告知"已无余票" → **生意被拒** | 不可接受，必须修复 |

所以自愈机制**只需要处理"偏少"这一侧**。这解释了为什么整个机制只在"令牌不足"的分支上挂载，而不是做一个双向校准。

### 8.2 自愈的完整代码

`TicketServiceImpl.java` L653–683：

```java
private final ScheduledExecutorService tokenIsNullRefreshExecutor = Executors.newScheduledThreadPool(1);   // ①

private void tokenIsNullRefreshToken(PurchaseTicketReqDTO requestParam, TokenResultDTO tokenResult) {
    RLock lock = redissonClient.getLock(String.format(LOCK_TOKEN_BUCKET_ISNULL, requestParam.getTrainId()));   // ②
    if (!lock.tryLock()) {
        return;                                                                                                // ③
    }
    tokenIsNullRefreshExecutor.schedule(() -> {                                                                // ④
        try {
            List<Integer> seatTypes = new ArrayList<>();
            Map<Integer, Integer> tokenCountMap = new HashMap<>();
            tokenResult.getTokenIsNullSeatTypeCounts().stream()
                    .map(each -> each.split("_"))                                                                  // ⑤
                    .forEach(split -> {
                        int seatType = Integer.parseInt(split[0]);
                        seatTypes.add(seatType);
                        tokenCountMap.put(seatType, Integer.parseInt(split[1]));
                    });
            List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(
                    Long.parseLong(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival(), seatTypes);   // ⑥
            for (SeatTypeCountDTO each : seatTypeCountDTOList) {
                Integer tokenCount = tokenCountMap.get(each.getSeatType());
                if (tokenCount <= each.getSeatCount()) {                                                           // ⑦
                    ticketAvailabilityTokenBucket.delTokenInBucket(requestParam);                                   // ⑧
                    break;
                }
            }
        } finally {
            lock.unlock();                                                                                     // ⑨
        }
    }, 10, TimeUnit.SECONDS);
}
```

逐点解释：

**① 单线程调度池。** `Executors.newScheduledThreadPool(1)` 是类字段，整个 JVM 只有 1 个线程跑复查任务。这是有意的限流：复查要查库，绝不能让成百上千个任务并发打数据库。

**② 分布式锁 `LOCK_TOKEN_BUCKET_ISNULL`**，Key 格式见 `RedisKeyConstant.java` L144：

```java
public static final String LOCK_TOKEN_BUCKET_ISNULL = "index12306-ticket-service:lock:token-bucket-isnull:%s";
```

粒度是**车次**（不是车次+席别），保证一趟车同一时间只有一个实例在做复查。

**③ `tryLock` 失败直接 `return`。** 不阻塞、不排队、不报错——因为"别的实例已经在处理了"，这次跳过就是最优解。

**④ 延迟 10 秒执行。** 这是整个机制的关键决策，见 8.3。

**⑤ 解析第一阶段留下的 `"2_3"` 字符串。** `split("_")` 得到 `[seatType, count]`，`seatTypes` 拿去查库，`tokenCountMap` 留着做比对。这就是 5.2 节 ⑤ 说"这个信息不是给用户看的"的答案。

**⑥ 查真实余票。** 用的是和初始化同一套 `listSeatTypeCount`（`SeatMapper.xml` L38–51），`seat_status = '0'` 才是可卖的。

这里还顺便解决了一个问题：**如果这个席别已经彻底卖光，SQL 的 `having seatCount > 0` 会让它不出现在结果集里**，下面的 `for` 循环自然不会命中它——恰好就是正确的行为（真没票，不该重建桶）。

**⑦ 核心判断：`tokenCount <= each.getSeatCount()`**

左边 `tokenCount` 是"用户想要的张数"（从 `"2_3"` 解析来的 3），右边是"数据库里还有多少张"。

- 如果**真实余票 ≥ 用户想要的**，说明"用户想要但被告知没票"这件事在数据库层面是不成立的 → **令牌桶的数偏小了**。
- 此时 `delTokenInBucket`（⑧）把整个 Hash 删掉。下次请求走 4.1.2 的惰性初始化路径，用最新的数据库余票重建桶。

**⑨ `unlock` 放在 `finally`。** 这里有一个**必须自己看出来**的细节：`tryLock()`（②）是在**请求线程**上执行的，而 `unlock()`（⑨）在**延迟任务的 lambda 里**，跑在 `tokenIsNullRefreshExecutor` 那个调度线程上——**两次调用不是同一个线程**。

Redisson 的 `RLock` 是**绑定线程**的：`RedissonLock.unlockAsync()` 内部会拿 `Thread.currentThread().getId()` 去比对锁的持有者（Lua 脚本里 `hexists KEYS[1] lockName:threadId`），对不上直接抛 `IllegalMonitorStateException`。所以这个 `unlock` 在运行期是会失败的（可对照项目所用 Redisson 版本的 `RedissonLock.unlockAsync` 源码确认这一点）。

顺带的后果是：锁会一直持有到租约到期（默认 30 秒）甚至更久，这倒是"歪打正着"地实现了"10 秒内不允许第二个复查任务"的效果，但它是**意外产生的**，不是设计意图。

读这段代码时应该建立的判断是：**任何 `tryLock` / `unlock` 配对，第一步就是确认它们是否在同一线程上。** 跨线程释放锁是分布式锁最常见的误用之一，正确做法是在同一个线程里用 `CompletableFuture` 或把锁的获取也搬进延迟任务内部。

### 8.3 为什么是 10 秒

延迟 10 秒是一个**用延迟换稳定**的取舍。

如果不延迟，立刻复查：

- 令牌不足的瞬间，说明**此刻确实没票**（大部分情况下）。立刻查库得到的结果必然是"真没票"，白查一次。
- 更糟的是"用户一退票就重建桶"：每次退票 → 回滚加令牌 → 但如果同时有失败请求在复查，就可能反复 `DEL` + 重建。而**每次重建都要遍历全趟区间打一批 SQL**（4.1.2 的 ④），这个成本是几十毫秒级 + N 次数据库查询。高频重建会直接打垮数据库。

延迟 10 秒带来的好处：

1. **让"自然恢复"先生效**。如果这 10 秒内有订单关闭（用户取消、30 分钟超时），`rollbackInBucket`（第 7 节）会把令牌加回桶里，桶自然就恢复了，根本不需要重建。
2. **把多次失败合并成一次复查**。10 秒内的所有失败请求，被 6.2 节的三层防重压成**一次**复查。
3. **减少误判**。10 秒后的数据库状态比失败瞬间更稳定。

**一句话总结这个设计**：令牌桶的"偏少"用一个廉价的、有延迟的、尽力而为的自愈机制来兜；而"偏多"根本不兜，交给后面的锁和事务。整个系统在"精确性"和"吞吐量"之间选择了后者，因为前者的收益远小于成本。

---

## 9. 时序图

### 图 A · 正常取令牌（桶已存在）

```text
 Caller          TicketAvailability        Redis              DB(t_seat)
 (purchaseTicket   TokenBucket
  sV2 L403)
   │                    │                    │                    │
   │──takeTokenFromBucket(requestParam)─────▶ │                    │
   │                    │                    │                    │
   │                    │─① hasKey 桶 key? ─▶│                    │
   │                    │◀──── true ─────────│                    │
   │                    │  （跳过初始化，不进分布式锁）              │
   │                    │                    │                    │
   │                    │ ② 组装两个 ARGV                         │
   │                    │   ARGV[1]=[{"seatType":"2","count":"2"}] │
   │                    │   ARGV[2]=[7 个受影响区间]               │
   │                    │                    │                    │
   │                    │─③ EVALSHA lua1 ───▶│                    │
   │                    │                    │ 阶段一: hget × 1    │
   │                    │                    │   10 >= 2 ✓        │
   │                    │                    │ 阶段二: hincrby × 7 │
   │                    │                    │   每个区间 -2       │
   │                    │◀─ {"tokenIsNull":false}                 │
   │◀─ tokenIsNull=false                     │                    │
   │                    │                    │                    │
   ▼                                                            │
 继续走双层锁 + 事务（S2C-3 / S2C-5） ────────────────────────▶ │
```

### 图 B · 令牌不足 + 10 秒后自愈

```text
 Caller          TokenBucket        Redis         Caffeine+锁     Executor(1线程)      DB
   │                  │               │               │                │              │
   │─takeToken(requestParam)─────────▶│               │                │              │
   │                  │──EVALSHA lua1▶│               │                │              │
   │                  │               │ 阶段一: hget 北京南_南京南_2 → 0       │
   │                  │               │   0 < 2  → tokenIsNull=true             │
   │                  │               │ 阶段一直接 return（一个令牌都不扣）      │
   │                  │◀─{"tokenIsNull":true,           │                │              │
   │                  │    "tokenIsNullSeatTypeCounts":["2_2"]}             │              │
   │                  │               │               │                │              │
   │                  │  ─④ refreshMap.getIfPresent(trainId) ─▶ null      │              │
   │                  │  ─⑤ synchronized(TicketService.class) ─▶ 得到锁   │              │
   │                  │  ─⑥ 再查一次 getIfPresent ─▶ null → put 标记      │              │
   │                  │  ─⑦ tokenIsNullRefreshToken() ──────────────────▶ │              │
   │                  │               │               │  tryLock(lock:token-bucket-isnull) │
   │                  │               │◀── 拿到锁 ────│                │              │
   │                  │               │               │  ⑧ schedule(..., 10s) ─▶ 挂起   │
   │◀─ throw ServiceException("列车站点已无余票")       │                │              │
   │                  │               │               │                │              │
   │  （此后 10 秒内，同一车次的所有失败请求在 ④ 处被 Caffeine 挡掉，
   │    不会触发第二次复查）                            │                │              │
   │                  │               │               │                │              │
   │                  │               │               │       ⏰ 10 秒后 │              │
   │                  │               │               │                │─⑨ 解析 "2_2"─│
   │                  │               │               │                │  seatType=2,count=2
   │                  │               │               │                │─⑩ listSeatTypeCount──▶
   │                  │               │               │                │◀─ seatCount=5 ────────
   │                  │               │               │                │  ⑪ 2 <= 5  ✓ 桶偏小
   │                  │◀─ delTokenInBucket(requestParam) ───────────────│              │
   │                  │──DEL 桶 key ─▶│               │                │              │
   │                  │               │               │                │─⑫ unlock（跨线程，见 8.2 ⑨）
   │                  │               │               │                │              │
   │                  ▼                                                              │
   │  下一个请求到达 → hasKey=false → 走 4.1.2 惰性初始化 → 用最新余票重建桶
```

### 图 C · 订单关闭回滚令牌

```text
  触发源（三选一）                      TokenBucket              Redis
   │                                       │                     │
   ├─ TicketServiceImpl.cancelTicketOrder L563（用户主动取消）      │
   ├─ DelayCloseOrderConsumer L125（MQ 延迟消息，30 分钟未支付）   │
   └─ OrderCloseCacheAndTokenUpdateHandler L69（Canal 监听 binlog） │
   │                                       │                     │
   │──rollbackInBucket(TicketOrderDetailRespDTO)─▶                │
   │                                       │                     │
   │       ① 从 passengerDetails 聚合出 {席别: 张数}              │
   │       ② listTakeoutTrainStationRoute 算出受影响区间          │
   │                                       │                     │
   │                                       │──EVALSHA lua2 ─────▶│
   │                                       │                     │ 双重循环：
   │                                       │                     │   hget 当前值
   │                                       │                     │   if 当前值 >= 0 then
   │                                       │                     │       hincrby +count
   │                                       │◀──── return 0 ──────│
   │                                       │                     │
   │       ③ 校验 result == 0L，否则 log.error + 抛 ServiceException
```

---

## 10. 数据流图：一次「北京南 → 南京南，2 张二等座」

前提：某高铁（`trainType = 0`），`t_train_station` 的出发站序列为
`[北京南, 济南西, 南京南, 杭州东, 宁波]`，车次首站 = 北京南、末站 = 宁波。

### 10.1 初始化阶段（第一次有人买这趟车的票）

```text
VehicleTypeEnum.findSeatTypesByCode(0)
        └─▶ seatTypes = [0, 1, 2]   （商务座 / 一等座 / 二等座）

throughStation([北京南,济南西,南京南,杭州东,宁波], 北京南, 宁波)
        └─▶ 穷举所有 i < j，共 10 个区间：
            北京南_济南西  北京南_南京南  北京南_杭州东  北京南_宁波
            济南西_南京南  济南西_杭州东  济南西_宁波
            南京南_杭州东  南京南_宁波
            杭州东_宁波

for 每个区间:
        seatService.listSeatTypeCount(trainId, 区间起, 区间止, [0,1,2])
                └─▶ SQL: select seat_type, count(*) from t_seat
                          where train_id=? and start_station=? and end_station=?
                            and seat_status='0' and seat_type in (0,1,2)
                          group by seat_type having seatCount > 0

putAll → Redis Hash: index12306-ticket-service:ticket_availability_token_bucket:1
   ┌────────────────────────────┬───────┐
   │ 北京南_济南西_0            │  8    │
   │ 北京南_济南西_1            │ 40    │
   │ 北京南_济南西_2            │ 120   │
   │ 北京南_南京南_1            │ 32    │
   │ 北京南_南京南_2            │ 10    │  ← 本次购票要读的就是这个 Field
   │ ...（共约 10 区间 × ≤3 席别）│       │
   └────────────────────────────┴───────┘

注意：`北京南_南京南_0`（商务座）这个 Field **根本不存在**——
初始化时该席别余票为 0，被 SQL 的 `having seatCount > 0` 过滤掉了。
这正是第 5.2 节 ④ 讨论的情况：如果用户买商务座，脚本的 `hget` 会读到 false。
```

### 10.2 取令牌阶段

```text
requestParam.passengers = [张三(二等座), 李四(二等座)]
        │
        ├─ groupingBy(seatType) + counting()
        │        └─▶ seatTypeCountMap = {2: 2}
        │                └─▶ ARGV[1] = [{"seatType":"2","count":"2"}]
        │
        └─ listTakeoutTrainStationRoute(trainId, 北京南, 南京南)
                 └─▶ takeoutStation([...5 站], 北京南, 南京南)
                          startIndex=0, endIndex=2
                          跳过 startIndex!=0 分支
                          i=0: j=1,2,3,4 → 北京南_济南西 北京南_南京南 北京南_杭州东 北京南_宁波
                          i=1: j=2,3,4   → 济南西_南京南 济南西_杭州东 济南西_宁波
                          i=2: i<endIndex 为假 → 无
                          └─▶ ARGV[2] = 7 个区间

KEYS[1] = index12306-ticket-service:ticket_availability_token_bucket:1
KEYS[2] = "北京南_南京南"  →  剥前缀 → actualKey = "北京南_南京南"

── EVAL lua1 ──────────────────────────────────────────────
阶段一（只看 1 个 Field）：
    actualInnerHashKey = "北京南_南京南_2"
    hget → 10
    10 < 2 ? 否 → tokenIsNull 保持 false
    ↓
阶段二（7 个 Field 各扣 2）：
    hincrby 北京南_济南西_2  -2     120 → 118
    hincrby 北京南_南京南_2  -2      10 →   8
    hincrby 北京南_杭州东_2  -2
    hincrby 北京南_宁波_2    -2
    hincrby 济南西_南京南_2  -2
    hincrby 济南西_杭州东_2  -2
    hincrby 济南西_宁波_2    -2
    ↓
return {"tokenIsNull": false}
```

**注意这三个数字的来源差异**：阶段一读 **1 个** Field（因为"够不够"取决于用户实际要坐的那一段），阶段二写 **7 个** Field（因为"占用"会波及所有跨过该段的区间）。**读少写多**是这段逻辑最容易被写错的地方。

### 10.3 后续闭环

| 事件 | 对令牌桶的写入 | 代码位置 |
| --- | --- | --- |
| 后续又有 4 个人各买 2 张 | `北京南_南京南_2`：10 → 8 → 6 → 4 → 2 | Lua 1 阶段二 |
| 第 6 个人来买 2 张 | 阶段一 `2 < 2` 为假，通过；阶段二扣到 **0** | Lua 1 阶段一 + 阶段二 |
| 第 7 个人来买 2 张 | 阶段一 `0 < 2` → `tokenIsNull=true`，**一行都不扣** → 抛异常 | Lua 1 阶段一 + `TicketServiceImpl` L415 |
| 10 秒后复查，DB 里真实余票已是 0 | `tokenCount(2) <= seatCount(0)` 为假 → 不重建桶 | `tokenIsNullRefreshToken` L672–678 |
| 张三取消订单 | `北京南_南京南_2` 加 2，其余 6 个区间各加 2 | Lua 2，`cancelTicketOrder` L563 |

---

## 11. 常量与 Key 速查

| 常量 / 配置 | 值 | 位置 |
| --- | --- | --- |
| `TICKET_AVAILABILITY_TOKEN_BUCKET` | `index12306-ticket-service:ticket_availability_token_bucket:` | `RedisKeyConstant.java` L114 |
| `LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET` | `index12306-ticket-service:lock:ticket_availability_token_bucket:%s` | `RedisKeyConstant.java` L139 |
| `LOCK_TOKEN_BUCKET_ISNULL` | `index12306-ticket-service:lock:token-bucket-isnull:%s` | `RedisKeyConstant.java` L144 |
| `TRAIN_STATION_REMAINING_TICKET` | `index12306-ticket-service:train_station_remaining_ticket:` | `RedisKeyConstant.java` L64 |
| `ADVANCE_TICKET_DAY` | `15`（天，`TRAIN_INFO` 缓存的过期时间） | `Index12306Constant.java` L29 |
| `LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH` | `lua/ticket_availability_token_bucket.lua` | `TicketAvailabilityTokenBucket.java` L78 |
| `LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH` | `lua/ticket_availability_rollback_token_bucket.lua` | `TicketAvailabilityTokenBucket.java` L79 |
| 自愈延迟 | `10` 秒 | `TicketServiceImpl.java` L682 |
| `tokenTicketsRefreshMap` 过期 | 10 分钟 | `TicketServiceImpl.java` L385 |
| 自愈线程池大小 | 1 | `TicketServiceImpl.java` L653 |
| 配置开关 | `ticket.availability.cache-update.type`（值 `binlog` 时走 Canal 路径） | `TicketServiceImpl.java` L160–161 |

---

## 12. 一页纸回顾

```text
问题   5000 个请求抢 10 张票，4990 个注定失败，却都要抢锁、开事务

思路   在 Redis 里放一份"库存的粗糙副本"，用一次原子脚本判断 + 扣减
       └ 精度要求低：多放了后面兜得住，少放了才要修

结构   Key   = ticket_availability_token_bucket:{trainId}
       Field = 出发站_到达站_席别
       Value = 剩余可扣数量（来自 t_seat 的快照）

写入   takeTokenFromBucket
       ├ 惰性初始化：双重检查 + Redisson 锁，遍历全趟区间 × 席别建桶
       ├ Lua 阶段一：只 HGET，任一席别不足 → 记录后直接 return，一行都不扣
       └ Lua 阶段二：全部充足 → 对 7 个受影响区间逐个 HINCRBY -count

回滚   rollbackInBucket（三个触发点，由配置开关二选一）
       └ Lua 2：HGET 判 >= 0 后 HINCRBY +count

自愈   tokenIsNullRefreshToken
       └ 三层防重 → 分布式锁 → 延迟 10 秒 → 查真实余票
         → 若真实余票 >= 用户想买的量，说明桶偏小 → DEL 桶，下次重建

边界   不保证不超卖；超卖由 S2C-3 双层锁 + S2C-4/S2C-5 座位分配与事务保证
```
