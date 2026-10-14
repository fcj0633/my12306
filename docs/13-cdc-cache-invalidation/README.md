# 库存 CDC 缓存失效

本次保留事务内 `t_inventory_cache_event` 及后台重试，新增独立 CDC 兜底通路。默认 Ticket CDC 关闭。库存事实源为 `t_seat_segment`，不是旧 `t_seat`。余票 27～30 秒 TTL、版本回填校验和数据库条件占座继续生效。

```text
t_seat_segment ROW/FULL Binlog
  → Canal Server 1.1.8 TCP（文件位点）
  → cdc-services（同步 RocketMQ client 5.5.0）
  → CacheInvalidationConsumer
  → InventoryCacheInvalidator
  → Redis 新随机版本 + 删除重叠 OD；释放时删除令牌桶
```

## 行为与边界

桥接每次拉取 50 个 Entry，逐条解析，消息按最多 100 行、256 KiB 分片。只有所有同步发送返回 `SEND_OK` 才 ACK Canal。任何解析、发送或 ACK 异常执行 rollback，重新连接后 1～30 秒退避。部分发送成功后失败允许重复；单行超限保留位点并报错。只运行一个桥接实例，不共用 destination 启动第二个进程。

V1 JSON 字段为 `schemaVersion,eventId,source,database,table,operation,binlogFile,binlogOffset,sourceTimestamp,publishedAt,rows`。每行 `before/after` 是列名到字符串或 JSON null 的映射；不存在的行镜像为 `{}`。必须包含 `seat_id,segment_index,seat_status,ticket_id,order_sn`。事件 ID 来自源标识、数据库、Binlog 文件/位置及分片序号，同一次位置重发 ID 不变。ID 不用来阻止再次失效。

可售条件严格为 `seat_status=0 AND ticket_id IS NULL AND order_sn IS NULL`。UPDATE 仅可售性或坐标改变时失效；`LOCKED→SOLD` 跳过。旧、新 seat_id 批量查物理座位，按车次收集区段。区段 s 影响且仅影响 `i≤s<j` 的 OD；多个区段取精确并集，不失效中间空隙。每次处理都调用公共组件，创建新随机版本并原子 DEL，从而兼容重复、乱序和双通路。

消费批量 1、线程 4、重试上限 16。整条消息处理完成才成功；失败返回 `RECONSUME_LATER`，最终进入 `%DLQ%<消费者组>`。部分车次成功、后续失败可重复处理前面车次。真实释放删除令牌桶后按既有聚合装载，不归还或扣减令牌。缺少物理座位、非法区段、协议损坏均重试并输出 ERROR。

第一版物理座位、站序不能在线删除或重排。迁移时停止写入、排空 Canal/MQ 后操作。表结构变更也应停写并按迁移流程协调。ROW/FULL、Binlog 保留期、Canal checkpoint 与 MQ 消息保留期都是恢复的前提；本轮不声称生产故障下绝不丢失。

## 配置、启动与停止

MySQL 开启 `log_bin`、`binlog_format=ROW`、`binlog_row_image=FULL`，使用独立复制账号：全局 `REPLICATION SLAVE, REPLICATION CLIENT`，目标库 `SELECT`。账号密码从环境变量或受保护临时配置产生，不提交仓库。

`services/cdc-services/deploy/compose.yml` 提供 Canal、NameServer、单 Broker 示例；一次性初始化容器为新 Broker 数据卷设置 uid 3000 写权限。复制 `broker.conf.example` 和 `instance.properties.example` 到自己的配置目录，替换复制账号和目标数据库，设 `CDC_CONFIG_DIR` 为绝对目录。Canal `canal.file.data.dir` 指向持久化卷；配置文件也挂载到容器。示例主机运行客户端，因此 Broker 广播 `127.0.0.1:10991`；跨主机部署需改为客户端可访问地址。Broker 使用 `SYNC_FLUSH`，这是单 Broker 示例，不代表高可用或同步复制。

Docker 所需环境：`CDC_CONFIG_DIR, CDC_DATABASE, CDC_MYSQL_USER, CDC_MYSQL_PASSWORD`；可选 `CDC_MYSQL_ADDRESS, CDC_SLAVE_ID, CDC_NAMESRV_PORT(19876), CDC_BROKER_PORT(10991), CDC_CANAL_PORT(11113)`。改变 Broker 端口同时修改 broker.conf。

```powershell
docker compose -p inventory-cdc -f services/cdc-services/deploy/compose.yml up -d
mvn -pl services/cdc-services -am package -DskipTests
```

桥接配置：`CDC_DATABASE` 必填；`CDC_SOURCE` 为稳定源标识；`CDC_CANAL_HOST/PORT/DESTINATION/USER/PASSWORD`，`CDC_NAMESRV`，`CDC_TOPIC`，`CDC_PRODUCER_GROUP`。使用示例 Docker 时设端口 11113 和 NameServer `127.0.0.1:19876`。运行 `java -jar services/cdc-services/target/cdc-services-0.0.1-SNAPSHOT.jar`。

首次启用先创建目标 Topic 并确认 NameServer 路由，再启动消费者并恢复写入。以默认 Topic 为例：

```powershell
docker compose -p inventory-cdc -f services/cdc-services/deploy/compose.yml exec broker sh mqadmin updateTopic -n namesrv:9876 -b 127.0.0.1:10991 -t my12306-inventory-cache-cdc -r 4 -w 4
docker compose -p inventory-cdc -f services/cdc-services/deploy/compose.yml exec broker sh mqadmin topicRoute -n namesrv:9876 -t my12306-inventory-cache-cdc
```

创建命令成功后路由注册仍可能等待一个 Broker 周期；看到 queueDatas 和 Broker 地址才表示 Topic 已就绪。示例映射端口只绑定宿主机 localhost。

Ticket 配置：`MY12306_CDC_ENABLED=true`，`MY12306_CDC_DATABASE` 与桥接相同，`MY12306_CDC_NAMESRV`，`MY12306_CDC_TOPIC`，`MY12306_CDC_CONSUMER_GROUP`。默认 Topic `my12306-inventory-cache-cdc`、组 `my12306-inventory-cache-cdc-cg`。CDC 与支付 `notify-mode` 无关，支付使用 Feign 时也可以启用。保留事务事件 `enabled=true`。

停止桥接后再停止消费者；普通 Docker 停止用 `docker compose ... down`，**不要加 `-v`**，保持位点及 Broker 数据卷。恢复使用相同 destination、源标识、库、消费组和卷。保留的 checkpoint 指向已删除 Binlog 时，应停写并核对库存/清空余票后建立新起点，不能静默跳到最新位置。回退关闭 Ticket CDC 和桥接，原事务事件与 TTL 继续工作。

## DLQ 查看与重放

使用 RocketMQ 自带 `mqadmin`，以实际 NameServer、组名替换示例。先查看组积压和失败日志，再查看 DLQ：

```text
mqadmin consumerProgress -n 127.0.0.1:19876 -g my12306-inventory-cache-cdc-cg
mqadmin printMsg -n 127.0.0.1:19876 -t %DLQ%my12306-inventory-cache-cdc-cg
```

修复 Redis、静态坐标或协议问题后，从 DLQ 导出并审查原始 V1 JSON body，使用本模块 `CdcReplayMain` 按文件重放到原 Topic。必须显式设置 `CDC_NAMESRV, CDC_TOPIC, CDC_DATABASE`，文件不要包含管理命令打印的消息头。重放校验库名和协议，只有同步 `SEND_OK` 才输出成功；保留 body 和 eventId。不要把 `%DLQ%` 广播给其他组。重放再次随机更新版本、DEL，安全但可能重复回源。核对成功、缓存收敛后再处理告警，不能把进入 DLQ 当成成功。重放工具不删除 DLQ 消息，便于审计。

```powershell
mvn -f services/cdc-services/pom.xml dependency:build-classpath '-Dmdep.outputFile=target/cdc-classpath.txt'
$cdcReplayCp = 'services/cdc-services/target/classes;' + (Get-Content services/cdc-services/target/cdc-classpath.txt -Raw).Trim()
java -cp $cdcReplayCp edu.swu.fcj.my12306.cdc.CdcReplayMain reviewed-V1-body.json
```

## 指标

桥接 Micrometer：`my12306.cdc.bridge.send.success/send.failure/ack/rollback`。Ticket：`my12306.cdc.consumer.received/success/retry/skipped/affected.keys/source.age/message.age`；公共失效：`my12306.inventory.invalidation.keys/duration`。不把事件 ID 或订单号作为标签。Ticket 现有 Actuator metrics 端点可读取。桥接为无 Web 进程，指标在 MeterRegistry 中；生产需另接监控导出器或启用管理端点。本轮日志用于追踪基础链路。

source.age 和 message.age 是事件年龄，不能代替数据库提交延迟。简单实验的计时从 JDBC commit 返回开始到 Redis 检查及加载完成，只有两个样本，不能作为 P99 或 SLA。双通路额外增加失效、版本 Key 与可能的重建成本，不宣称吞吐提升。

## 复现简单验证

在安装 Maven、Java 21、MySQL 客户端、Docker、Python PyYAML 的主机运行：

```powershell
python services/cdc-services/tools/simple-chain.py
```

读取 Ticket 当前连接配置，只读复制合法 train=1 数据到唯一 `codex_cdc_test_<时间戳>`，迁移基本区段，创建临时复制账号。独立 Docker 项目、Topic/组以及 `codex:cdc:<时间戳>:` Redis 前缀，业务库不写入，既有容器不停止。验证直接 SQL 占座和释放；每次失效等待最多 10 秒，保留相邻缓存，重载与数据库核对。退出恢复测试库存、删命名空间和临时库/账号，`down -v` 仅用于本轮独立 Docker 项目。证据输出在 `docs/13-cdc-cache-invalidation/evidence`，日志/临时配置在模块 `target/simple-chain`。

ACK 行为参考 [Canal 官方客户端说明](https://github.com/alibaba/canal/wiki/ClientExample)；文件位点配置参考 [1.1.8 file-instance.xml](https://github.com/alibaba/canal/blob/canal-1.1.8/deployer/src/main/resources/spring/file-instance.xml)。
