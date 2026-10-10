"""Analyze the balanced current-version 20-client diagnostic experiment."""
import collections,csv,datetime,json,pathlib,re,statistics
HERE=pathlib.Path(__file__).resolve().parent;OUT=HERE/'results/stages'
def load(name):return json.loads((OUT/name).read_text('utf-8'))
def quantile(values,q):return sorted(values)[max(0,__import__('math').ceil(len(values)*q)-1)]
def describe(values):return dict(mean=statistics.mean(values),median=statistics.median(values),p95=quantile(values,.95),p99=quantile(values,.99),min=min(values),max=max(values))
records=load('stage-confirm-matrix.json');assert len(records)==9 and all(r['valid'] for r in records)
groups=collections.defaultdict(list)
for r in records:groups[(r['clientMode'],r['probeEnabled'])].append(r)
summaries={}
for (mode,enabled),rs in groups.items():
    assert len(rs)==3
    summaries[f'{mode}/{enabled}']=dict(qps=describe([r['successQps'] for r in rs]),
        hold=describe([r['holdAvgMs'] for r in rs]),p99=statistics.median(r['quantiles']['success']['0.99'] for r in rs),
        requests=sum(r['success'] for r in rs))
    if not enabled:continue
    stages=collections.defaultdict(list);derived=collections.defaultdict(list);gaps=[];cycles=[];by_round=[];http=[]
    for r in rs:
        rows=list(csv.DictReader((OUT/(r['tag']+'-traces.csv')).open(encoding='utf-8')))
        http+= [int(x['elapsed']) for x in csv.DictReader((OUT/f"s1-20-{r['tag']}.jtl").open(encoding='utf-8-sig'))]
        traces=collections.defaultdict(dict)
        for row in rows:traces[row['id']][row['stage']]=row
        assert len(traces)==r['success']
        for trace in traces.values():
            for phase,row in trace.items():stages[phase].append(int(row['durationNs'])/1e6)
            def start(p):return int(trace[p]['stageStartNs'])
            def end(p):return int(trace[p]['stageEndNs'])
            def duration(p):return int(trace[p]['durationNs'])
            root=trace['purchase.total']
            derived['post-acquire-before-tx'].append((start('tx.proxy')-int(root['seatAcquireNs']))/1e6)
            derived['tx.begin'].append((start('tx.body')-start('tx.proxy'))/1e6)
            derived['tx.finish'].append((end('tx.proxy')-end('tx.body'))/1e6)
            derived['seat.acquired-to-unlock-start'].append((int(root['unlockStartNs'])-int(root['seatAcquireNs']))/1e6)
            inside=['train.cache','jdbc.station-relation','seat.allocate','jdbc.seat-update','price.lookup','jdbc.ticket-insert']
            residual=(duration('tx.body')-sum(duration(p) for p in inside))/1e6
            assert residual>=-.05
            derived['tx.body.other'].append(residual)
        ordered=sorted((t['purchase.total'] for t in traces.values()),key=lambda t:int(t['seatAcquireNs']))
        rg=[(int(b['seatAcquireNs'])-int(a['unlockStartNs']))/1e6 for a,b in zip(ordered,ordered[1:])]
        rc=[(int(b['seatAcquireNs'])-int(a['seatAcquireNs']))/1e6 for a,b in zip(ordered,ordered[1:])]
        assert all(v>0 for v in rg)
        gaps+=rg;cycles+=rc
        by_round.append(dict(tag=r['tag'],qps=r['successQps'],gap=statistics.mean(rg),cycle=statistics.mean(rc)))
    summaries[f'{mode}/{enabled}'].update(stages={k:describe(v) for k,v in stages.items()},
        derived={k:describe(v) for k,v in derived.items()},handoff=describe(gaps),lockCycle=describe(cycles),byRound=by_round,
        http=describe(http),httpOutsideServiceMeanMs=statistics.mean(http)-statistics.mean(stages['purchase.total']))
body=['# 20并发购票阶段诊断报告','',
 '业务版本：22b0405，未编辑业务源码或重建业务JAR。单Ticket实例，同一批车次1/席别2/北京南→宁波810张原始座位，每轮开始全部可售。','',
 '## 测试方法','',
 '- 使用本地Byte Buddy 1.14.19的premain探针，按购票请求记录方法调用和单调时钟边界。仅捕获当前请求线程中的关键方法，异步消息任务不在该链路内。诊断数据由独立线程批量写文件，队列非阻塞；每个开启诊断的正式轮次完整trace数量与HTTP成功数一致。',
 '- 直接测量MySQL JDBC Connection.commit、PreparedStatement.execute等调用，区分JDBC往返与数据库performance_schema执行耗时；JDBC commit含驱动提交逻辑，不等于只有服务器COMMIT语句。',
 '- 小堆配置与上一轮一致，关闭历史孤儿扫描和订单兜底扫表，保留延迟关单。最大堆Ticket/User/Nacos/JMeter256MB、Order192MB、Gateway160MB；记录Windows资源与GC。',
 '- 初始序列明显受冷态影响，数据全部保留，单独说明；再完成4轮诊断预热后，采用三组顺序轮换：关闭/共享/逐线程 → 共享/逐线程/关闭 → 逐线程/关闭/共享，各组3轮、每轮8秒。全部为当前版本，不测旧版A。',
 '- 诊断关闭仍加载agent并保留轻量入口检查，因此不等价于完全无agent；关闭/开启用于估计收集阶段数据的额外影响。共享与逐线程客户端使用相同计时、屏障、用户、库存、指标采样及请求保护。',
 '- QPS窗口为首个HTTP发送至最后完成，包含排空；P99来自JTL。各阶段统计为该设置三轮全部请求的合并分布，阶段均值不是轮次中位数。父/子阶段有嵌套，不能把表中所有行相加。','',
 '## 平衡顺序的正式结果','',
 '|设置|3轮成功QPS|QPS中位数|持锁均值中位数 ms|HTTP P99中位数 ms|成功请求合计|',
 '|---|---|---:|---:|---:|---:|']
for key,s in summaries.items():
    rs=groups[(key.split('/')[0],key.endswith('True'))]
    qps_text=' / '.join(format(r['successQps'],'.2f') for r in rs)
    body.append(f"|{key}|{qps_text}|{s['qps']['median']:.2f}|{s['hold']['median']:.2f}|{s['p99']}|{s['requests']}|")
main=summaries['shared/True'];d=main['derived'];st=main['stages']
body+=['','## 共享客户端下的阶段分布','',
 '|阶段|平均 ms|P50 ms|P95 ms|P99 ms|说明|','|---|---:|---:|---:|---:|---|']
labels=[('purchase.total','购票服务方法总耗时','不含进入服务方法前的Gateway/认证/排队'),
 ('chain.validation','责任链校验','包含购票前车站列表查询'),('token.admission','令牌准入','包括Redis访问与Lua'),
 ('passenger.remote','乘车人远程查询','含结果校验/组装'),('user.lock-wait','用户锁获取','包含Redis往返，不全是争用等待'),
 ('seat.lock-wait','席别锁获取/等待','包含争用、网络及调度'),('post-acquire-before-tx','取得锁到进入事务代理','包含现有INFO日志和Timer等'),
 ('tx.proxy','事务代理完整调用','包含事务开始、方法体、提交和清理'),('tx.begin','事务进入','嵌套于事务代理'),
 ('tx.body','事务方法体','嵌套于事务代理'),('train.cache','车次缓存读取/反序列化','嵌套于方法体'),
 ('jdbc.station-relation','站点关系JDBC执行','不含完整ORM结果映射'),('seat.allocate','完整选座分配','含JDBC查询与结果读取/映射'),
 ('jdbc.seat-select','选座JDBC执行','嵌套于选座分配'),('jdbc.seat-update','条件占座JDBC执行','不含全部ORM处理'),
 ('price.lookup','完整票价查询','含JDBC及结果映射'),('jdbc.ticket-insert','车票插入JDBC执行','不含全部ORM处理'),
 ('tx.body.other','方法体其余成本','包含未单独测量的映射/框架/DTO组装等'),('tx.finish','方法返回到事务代理返回','包含提交、连接重置与清理'),
 ('jdbc.commit','JDBC提交调用','嵌套于事务结束，包含驱动逻辑'),('seat.unlock-safe','席别锁安全释放','包含归属检查与解锁'),
 ('seat.ownership-check','席别锁归属检查','嵌套于安全释放，涉及Redis'),('seat.unlock-rpc','席别锁unlock调用','嵌套于安全释放'),
 ('user.unlock-safe','用户锁安全释放','用户锁独立，不作为席别临界区'),('remaining-cache.evict','余票缓存失效','席别锁外'),
 ('order.remote','远程建单','席别锁外')]
for key,label,note in labels:
    v=(d if key in d else st)[key]
    body.append(f"|{label}|{v['mean']:.3f}|{v['median']:.3f}|{v['p95']:.3f}|{v['p99']:.3f}|{note}|")
body+=['','## 锁交接与串行服务周期','',
 f"HTTP平均耗时{main['http']['mean']:.3f}ms，购票服务方法平均耗时{st['purchase.total']['mean']:.3f}ms；两者均值差{main['httpOutsideServiceMeanMs']:.3f}ms。差值包括客户端/网络、Gateway、进入业务方法前后的过滤器/调度等，未进一步拆开，不冒称全部网关耗时。",'',
 f"同一轮按席别锁获取时刻排序，相邻获取间隔均值 **{main['lockCycle']['mean']:.3f}ms**。取得锁至开始unlockSafely均值 **{d['seat.acquired-to-unlock-start']['mean']:.3f}ms**；从本次unlockSafely开始至下一请求取得锁均值 **{main['handoff']['mean']:.3f}ms**。",'',
 '后者包含归属检查、解锁、下一请求获锁与调度，不能全部叫Redis网络时间。使用解锁开始而非完成作为边界，因为下一线程可能在上一线程收到解锁回复前取得锁。上述时序只用于本轮单key单席别场景；不是所有锁粒度的一般模型。','',
 f"原持锁Timer从事务代理调用前开始，至返回后结束，未覆盖上述完整交接成本。因此 **1000 / hold 不是接口上限**；实测相邻获锁间隔的倒数约 **{1000/main['lockCycle']['mean']:.2f}/s**，也不能等同于长时间稳定HTTP容量。",'',
 '## SQL与资源证据','']
for key,rs in groups.items():
    costs=collections.defaultdict(lambda:[0,0,0])
    for r in rs:
        for digest,a in r['dbAfter']['digest'].items():
            b=r['dbBefore']['digest'].get(digest,dict(count=0,time=0,rows=0));count=a['count']-b['count']
            if count>0:
                v=costs[a['text']];v[0]+=count;v[1]+=(a['time']-b['time'])/1e9;v[2]+=a['rows']-b['rows']
    summary=sorted([dict(sql=sql,calls=v[0],meanMs=v[1]/v[0],examinedPerCall=v[2]/v[0]) for sql,v in costs.items()],key=lambda x:x['meanMs'],reverse=True)
    (OUT/f"sql-{key[0]}-{key[1]}.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    body+=['|设置|SQL|平均执行 ms|检查行数/次|','|---|---|---:|---:|']
    for v in summary:
        if v['sql']=='COMMIT' or v['sql'].startswith('SELECT `id` , `carriage_number` , `seat_number`'):
            body.append(f"|{key}|{'COMMIT' if v['sql']=='COMMIT' else 'LIMIT选座'}|{v['meanMs']:.3f}|{v['examinedPerCall']:.1f}|")
    body+=['']
body+=['![20并发阶段耗时](./20并发阶段耗时.png)','','## 原因分析与优化顺序','',
 f"**主要请求延迟来自席别锁争用。** 在主要诊断组，获锁等待均值{st['seat.lock-wait']['mean']:.2f}ms，占业务方法总耗时约{st['seat.lock-wait']['mean']/st['purchase.total']['mean']*100:.1f}%。这定位了请求在哪里等待，但决定等待增长速度的是串行服务周期，不能只把等待时间缩短作为解决方案。",'',
 f"**单条选座查询不是当前主要成本。** 完整分配均值{st['seat.allocate']['mean']:.2f}ms，数据库执行约1ms/次且检查1行。事务方法体仍有车次缓存、站点关系、票价、条件占座、插票等工作，合计均值{st['tx.body']['mean']:.2f}ms；JDBC提交均值{st['jdbc.commit']['mean']:.2f}ms，需另外算事务进入、清理。",'',
 f"**原hold漏掉了真实锁服务周期的一部分。** 事务代理均值{st['tx.proxy']['mean']:.2f}ms之外，取得锁到进入事务代理约{d['post-acquire-before-tx']['mean']:.2f}ms；释放/交接约{main['handoff']['mean']:.2f}ms。这解释了为什么按1000/hold计算约57/s，HTTP却只约38/s：单key相邻获锁约24ms，还有HTTP有限窗口的起跑/排空等影响。",'',
 '**当前代码可出现接近历史50QPS的轮次，但未证明稳定50QPS。** 诊断关闭三轮27.28/49.82/35.05，持锁24.82/13.40/19.11ms；同代码和同810库存下也有明显变化。初始诊断序列从持锁60ms以上逐步降到20ms附近，表明冷态/运行状态影响很大；这不是排除一切代码成本的证据，也不能把唯一最快轮次当作容量。', '',
 '**没有证据把共享客户端定为主要原因。** 共享开启诊断中位38.01QPS，逐线程42.48QPS，但逐线程三轮30.47/46.91/42.48波动较大，且对应事务耗时也变化。两种客户端业务方法外的HTTP均值差都约56～58ms，不能说共享客户端独有这段损耗。诊断开启没有稳定比关闭更慢，但关闭组波动明显，无法精确估计探针开销百分比；关闭也未卸载agent。', '',
 '建议优先验证：①把车次、站点关系、票价等相对稳定的只读数据准备/缓存移出占座临界区，保留一致性和失效规则；本轮三个读取阶段合计约4.8ms，这是可调查空间，不是已测提升。②已记录实际取得锁的前提下，评估减少释放前isHeldByCurrentThread的独立Redis往返；Redisson3.31.0的unlock Lua本身校验owner，仍需保留租约失效/异常处理并验证多实例安全，不能直接删检查后宣称正确。③进一步测量MySQL提交/fsync与I/O，保留当前持久性；④拆分锁后事务前约1.1ms中的INFO日志/计时/框架成本，再决定日志移位或采样。','',
 '本次没有实施上述业务优化，不降低数据库持久性，不改变锁粒度或选座算法。没有旧版同环境阶段数据，历史50到本轮35的差值仍不能唯一归因；本次给出的是当前成本分布、时序和运行状态证据。','',
 '## 资源与收尾','']
machine=[]
for line in (OUT/'machine-resources.jsonl').read_text('utf-8-sig').splitlines():
    point=json.loads(line);stamp=re.sub(r'(\.\d{6})\d+',r'\1',point['utc'])
    point['epoch']=datetime.datetime.fromisoformat(stamp.replace('Z','+00:00')).timestamp();machine.append(point)
body+=['|正式轮次|成功QPS|CPU中位数 %|最低可用内存 GB|page reads/s采样峰值|Windows采样数|','|---|---:|---:|---:|---:|---:|']
for r in records:
    ps=[p for p in machine if r['httpStartMs']/1000<=p['epoch']<=r['httpEndMs']/1000]
    body.append(f"|{r['tag']}|{r['successQps']:.2f}|{statistics.median(p['cpuPercent'] for p in ps):.1f}|{min(p['freeMemoryGB'] for p in ps):.3f}|{max(p['pageReadsPerSec'] for p in ps)}|{len(ps)}|")
body+=['','主要正式窗口最低可用内存2.208GB，分页采样峰值不高，但每轮只有1～2个Windows点，不足以排除瞬时干扰。较快轮次CPU采样较低，不能据少量点认定CPU就是唯一原因。不能将本轮全部差异归因于内存不足。','']
final=load('final-verification.json');shutdown=json.loads((OUT/'shutdown-verification.json').read_text('utf-8-sig'));scheduled=load('scheduled-cleanup-verification.json')
body += [f"初始正式9轮加平衡正式9轮，共{final['formalRounds']}轮、{final['successfulPurchases']:,}次成功；正式令牌拒绝/降级0、无保护上限触发。每轮成功数与订单/明细/车票/座位减少数一致。结束810可售、0占座；现存令牌field须为810，最终检查值为{final['token']}（None表示已按TTL过期，将由应用重新装载）。本轮新增订单/明细/车票残留0，JAR哈希与先前HEAD构建一致。",'',
 f"停止本次5个服务与监控，端口无监听，可用内存{shutdown['freeMemoryGB']:.3f}GB；移除{scheduled['removedEntries']:,}条属于本轮已删除订单的延迟任务，不清空其他缓存或用户。",'',
 '|服务|正式窗口暂停数|Full GC数|最长暂停 ms|累计暂停 ms|','|---|---:|---:|---:|---:|']
for service,g in final['gc'].items():body.append(f"|{service}|{g['pauseCount']}|{g['fullCount']}|{g['maxPauseMs']:.3f}|{g['totalPauseMs']:.3f}|")
body += ['', '服务未见OOM、正式窗口无Full GC；累计暂停是该服务的日志事件统计，边界按实际JVM起始时刻对齐，不能把不同服务暂停时长直接相加。JMeter启动的System.gc()另计。探针首次中文目录参数编码异常已在正式测量前通过Base64参数修复并重启Ticket，初始化证据保留。','',
 '业务源码保持不变。本次新增诊断agent、运行/分析/核验工具及报告，启动/停止/资源脚本新增独立输出目录参数；没有提交、合并或推送。','',
 '## 解释边界','',
 '本报告不把不同会话的历史50QPS与本轮比值当成代码回归比例。旧报告20并发实际起点668/717/673张，本轮全部810张；运行资源、探针及客户端配置也有差异。没有旧版同环境对照，无法唯一归因历史差值。',
 '初始序列、冷启动、全部正式JTL与trace都保留在results/stages。平衡顺序数据用于主要分析；初始序列不伪装成稳态，也不删除慢轮次。库存、令牌、订单与车票逐轮对账/释放，资源与最终收尾另附。','']
(OUT/'stage-summary.json').write_text(json.dumps(summaries,ensure_ascii=False,indent=2),encoding='utf-8')
(HERE/'20并发购票-阶段诊断报告.md').write_text('\n'.join(body),encoding='utf-8')
print(json.dumps({k:dict(qps=v['qps'],hold=v['hold'],requests=v['requests']) for k,v in summaries.items()},ensure_ascii=False))
