# 20并发阶段诊断探针

`StageAgent.java` 使用本机Byte Buddy 1.14.19的premain转换目标方法，未编辑购票业务源码。匹配业务服务、事务拦截器、SeatAllocator、Redisson锁、Hikari及MySQL JDBC客户端。仅购票服务方法开启一个线程上下文；其他请求、后台任务不记录。

## 本轮复现入口

1. 使用Java21的javac编译Agent，classpath指向本机 `.m2/repository/net/bytebuddy/byte-buddy/1.14.19/byte-buddy-1.14.19.jar`。manifest配置 `Premain-Class: perfdiag.StageAgent`，`Class-Path`指向该依赖的file URL；jar打包编译类与manifest。
2. 调用同目录上级 `start-current-services.ps1`，提供独立 `-OutputDirectory .../results/stages` 及 `-TicketJavaAgent .../stage-agent.jar`。脚本只为Ticket添加探针。目录参数按UTF-8/Base64传入，避免Windows JVM agent中文参数编码问题。
3. 启动资源监控并记录PID。执行 `run-stages.py smoke`，验证成功购票与完整阶段记录；执行 `matrix` 观察预热趋势和当前版本诊断开关；`client`补充逐线程客户端；`confirm`以三组轮换顺序收集主要分析数据。每轮前810可售、令牌一致，每轮结束取消/清理本轮交易。
4. 保存实际JVM的service、ProcessId、CreationDate到 `results/stages/process-start-times.json`，使用独立输出目录调用停止工具；运行 `cleanup-scheduled-tests.py --output-dir .../results/stages`，只移除本轮日志识别且数据库已清理的延迟任务。
5. 执行 `verify-stages.py`、`analyze-stages.py`、`plot-stages.py`。核验脚本的日期范围、提交/JAR指纹和场景数量对应本轮，新的会话需更新，不直接沿用旧PID或旧范围。

## 边界与开销

捕获开关为本地 `capture.flag`，写线程定期读取。方法内只做单调时钟计时和请求内统计，完成后投递至有界队列，由独立线程写CSV；正式完整trace数必须等于HTTP成功数。CSV包含阶段首/末时刻和累计耗时，父子阶段有重叠。

关闭捕获不会卸载Agent，仍有入口检查。三轮波动较大时不能给出精确探针开销比例，也不能把共享/逐线程客户端的中位差直接视作因果收益。JDBC execute不含全部ResultSet/ORM映射，commit含驱动逻辑；SQL服务器耗时另用摘要差值。

锁交接取unlockSafely开始到下一请求获锁，包含owner检查、解锁、网络/调度和获锁，不能全部归因Redis往返。仅对本轮单席别、单key、单实例且正式请求全部成功的序列分析；异步线程、多人多锁和跨OD正确性不在本轮结论范围。

所有原始数据及JWT文件位于被忽略的results目录，本轮未提交或推送。

## 持续客户端及锁外元数据对照

`WarmPurchaseSampler.java`是编译后的JMeter采样器。100工作线程和共享HttpClient在同一JVM持续存在，由输出目录的`command.json`切换并发/预热/正式/停止阶段；未参与档位的线程等待。HTTP同步发送到完整响应读取使用单调时钟计时，JSON分类单独计时，排空请求也保存。请求头`X-Perf-Request-Id`及结果订单号用于关联证据。控制文件含目录信息，登录文件含会话，结果目录继续保持Git忽略。

`warm-runner.py`的真实预热每批20并发、每线程5次，共100笔；最多五批检查相邻HTTP和业务方法中位数±15%。不稳定的一组作为探索排除；`warm-matrix.py`最多三组独立确认，仍不稳定则停止正式比较。这个判据是进入短窗口测试的条件，不能证明后续长期稳态。每次部署后刷新400会话，避免30分钟登录过期。短窗口使用405请求保护和810库存逐轮对账，排空计入成功QPS。

先设置`WARM_OUTPUT`为独立绝对结果目录、`WARM_INJECTOR_HEAP`为最终比较堆；准备并核对基线和候选同名JAR、810库存和源码指纹，启动Compact服务及阶段代理。`warm-runner.py baseline`启动持续客户端、简单接口/真实预热及探索基线；`warm-matrix.py --new-series --series <未用序号>`交替部署三组基线/候选。不要复用已有阶段标签或将改变窗口的旧轮次混入正式矩阵。探索重启客户端后重新完成所有线程简单接口预热和真实预热，完整比较来自同一个新客户端。

仅在正式方法外均值超过HTTP均值25%时，另行实施Gateway/Ticket入口诊断；未触发时不继续扩展探针。

完成后执行三轮10票超订、停止客户端和自有服务、按日志订单号清理已删除交易的延迟任务，执行`run-carriage.py verify`、`verify-warm.py`、`analyze-warm.py`和`write-warm-report.py`。核对目标池外快照、原测试车票ID、49项最终测试XML和候选JAR指纹；只有全部核验通过才生成最终结论。

## 车厢锁版本（2026-10-09）

新增`reserveWithCarriageLocks`和`allocateInCarriage`捕获；同一请求的阶段耗时/次数可累计多次事务尝试，另外写入`locks.csv`保存每个锁key的独立获取、归属检查、解锁及安全释放时刻。旧CSV的seatAcquire/unlock字段只是最后一次记录，不用于多锁分析。

使用`run-carriage.py`和`analyze-carriage.py`，输出目录为`results/carriage`。单key交接统计按具体key及轮次划分；不同车厢临界区交叠使用同一Ticket JVM的单调时钟判断，不跨JVM比较System.nanoTime。最终六轮没有候选或数据库重试，事务边界按每请求一次解释；以后出现重试时必须拆分独立事务事件后分析边界，工具会拒绝直接合并解释。

`CarriageGuard.java`在第三个JVM持有正式车厢锁key，用于验证9002和9012均等待这些锁，释放后才占座；它不是性能负载。仅从环境变量读取Redis密码。Windows Java参数文件使用ASCII及Base64目录参数，编译输出放在项目target下，避免中文路径编码问题。
