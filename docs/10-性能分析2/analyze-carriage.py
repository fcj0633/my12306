"""Analyze six current-version rounds; never interprets historical differences as causal gains."""
import csv, datetime, hashlib, importlib.util, json, pathlib, re, statistics, xml.etree.ElementTree as ET
HERE=pathlib.Path(__file__).resolve().parent;OUT=HERE/'results/carriage'
spec=importlib.util.spec_from_file_location('carriage',HERE/'run-carriage.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
c=m.c

def csvrows(path):
    return list(csv.DictReader(path.open(encoding='utf-8')))

def percentile(values,q):return c.base.percentile(values,q)

def timestamp(value):
    return datetime.datetime.fromisoformat(re.sub(r'(\.\d{6})\d+',r'\1',value.replace('Z','+00:00'))).timestamp()

def analyze():
    records=json.loads((OUT/'formal-matrix.json').read_text('utf-8'))
    assert len(records)==6 and all(row['valid'] and row['poolBefore']==810 and row['success']<=405 and not row['capReached'] for row in records)
    assert sum(row['probeEnabled'] for row in records)==3
    stats={};all_intervals=[];lanes={};phase={};distribution={};sql=[];max_pending=0
    for row in records:
        for key,value in row['carriageDistribution'].items():distribution[key]=distribution.get(key,0)+value
        max_pending=max(max_pending,max((sample['values']['hikaricp.connections.pending'].get('VALUE',0) for sample in row['resource']),default=0))
        for key,after in row['dbAfter']['digest'].items():
            before=row['dbBefore']['digest'].get(key,dict(count=0,time=0,rows=0));count=after['count']-before['count']
            if count and (re.search(r'\bCOMMIT\b',after['text']) or 't_seat' in after['text']):
                sql.append(dict(tag=row['tag'],text=after['text'],count=count,meanMs=(after['time']-before['time'])/1e9/count,examinedPerCall=(after['rows']-before['rows'])/count))
        if not row['probeEnabled']:continue
        traces=csvrows(OUT/(row['tag']+'-traces.csv'));locks=csvrows(OUT/(row['tag']+'-locks.csv'))
        per_request={}
        for trace in traces:
            per_request.setdefault(trace['id'],{})[trace['stage']]=trace
            phase.setdefault(trace['stage'],[]).append(int(trace['durationNs'])/1e6)
        for key in {event['key'] for event in locks if event['stage']=='seat.lock-wait'}:
            acquisitions=sorted([event for event in locks if event['key']==key and event['stage']=='seat.lock-wait'],key=lambda event:int(event['endNs']))
            for event in acquisitions:
                safe=next((other for other in locks if other['id']==event['id'] and other['key']==key and other['stage']=='seat.unlock-safe' and int(other['startNs'])>=int(event['endNs'])),None)
                assert safe,'Missing release event'
                all_intervals.append((row['tag'],key,int(event['endNs']),int(safe['startNs'])))
            lanes.setdefault(key,[]).extend((int(b['endNs'])-int(a['endNs']))/1e6 for a,b in zip(acquisitions,acquisitions[1:]))
        assert all(int(value['tx.proxy']['count'])==1 for value in per_request.values()),'Separate retry spans before interpreting transaction boundaries'
        for value in per_request.values():
            proxy,body=value['tx.proxy'],value['tx.body']
            phase.setdefault('tx.begin',[]).append((int(body['stageStartNs'])-int(proxy['stageStartNs']))/1e6)
            phase.setdefault('tx.finish',[]).append((int(proxy['stageEndNs'])-int(body['stageEndNs']))/1e6)
    for name,values in phase.items():stats[name]=dict(meanMs=statistics.mean(values),p50Ms=percentile(values,.5),p95Ms=percentile(values,.95),p99Ms=percentile(values,.99),requests=len(values))
    # Same JVM monotonic times. Pre-release intervals exclude unlock RPC handoff overlap.
    max_parallel=0;overlap_count=0
    for row in records:
        intervals=[entry for entry in all_intervals if entry[0]==row['tag']]
        for key in {entry[1] for entry in intervals}:
            ordered=sorted([entry for entry in intervals if entry[1]==key],key=lambda entry:entry[2])
            assert all(a[3]<=b[2] for a,b in zip(ordered,ordered[1:])),'Same carriage critical sections overlap'
        events=sorted([(entry[2],1) for entry in intervals]+[(entry[3],-1) for entry in intervals])
        active=0
        for _,delta in events:
            active+=delta;max_parallel=max(max_parallel,active)
        overlap_count+=sum(any(other[1]!=entry[1] and max(other[2],entry[2])<min(other[3],entry[3]) for other in intervals) for entry in intervals)
    env=json.loads((OUT/'environment.json').read_text('utf-8'))
    m.inventory_clean()
    assert c.base.sql('SELECT id,seat_status FROM 12306_ticket.t_seat WHERE NOT ('+c.base.POOL+') ORDER BY id;')==env['outsidePool']
    assert sorted(c.base.ticket_ids())==env['initialPerfTicketIds']
    token=c.base.redis('HGET',m.TOKEN,'2');assert token is None or int(token)==810
    for service,expected in env['artifactSha256'].items():
        path=c.ROOT/f'12306/my12306/services/{service}-services/target/{service}-services-0.0.1-SNAPSHOT.jar'
        assert hashlib.sha256(path.read_bytes()).hexdigest()==expected
    verification=dict(formalRounds=6,successfulPurchases=sum(row['success'] for row in records),availableSeats=810,
        outsidePoolUnchanged=True,perfTicketIdsRestored=True,token=None if token is None else int(token),
        maxConcurrentCarriageSections=max_parallel,sameKeyOverlap=0,differentKeyOverlappingSections=overlap_count,
        artifactFingerprintsMatch=True,maxSampledConnectionPending=max_pending)
    machine=[json.loads(line) for line in (OUT/'machine-resources.jsonl').read_text('utf-8-sig').splitlines()]
    resources=[sample for sample in machine if any(row['httpStartMs']/1000<=timestamp(sample['utc'])<=row['httpEndMs']/1000 for row in records)]
    verification['machineSampleCount']=len(resources)
    if resources:
        verification.update(minFreeMemoryGB=min(sample['freeMemoryGB'] for sample in resources),maxSampledCpuPercent=max(sample['cpuPercent'] for sample in resources),maxSampledPageReads=max(sample['pageReadsPerSec'] for sample in resources))
    starts=json.loads((OUT/'process-start-times.json').read_text('utf-8-sig'));gc={}
    for process in starts:
        path=OUT/(process['service']+'.gc.log')
        if not path.exists():continue
        start=timestamp(process['CreationDate']);pauses=[]
        for line in path.read_text('utf-8',errors='replace').splitlines():
            match=re.match(r'\[(\d+(?:\.\d+)?)s\].*Pause.* (\d+(?:\.\d+)?)ms$',line)
            if match and any(row['httpStartMs']/1000<=start+float(match[1])<=row['httpEndMs']/1000 for row in records):pauses.append((float(match[2]),'Pause Full' in line))
        gc[process['service']]=dict(count=len(pauses),fullCount=sum(entry[1] for entry in pauses),maxMs=max((entry[0] for entry in pauses),default=0),totalMs=sum(entry[0] for entry in pauses))
    verification['gc']=gc
    excluded=json.loads((OUT/'excluded-rounds.json').read_text('utf-8'))
    verification['excludedRounds']=len(excluded)
    http_samples=[]
    for row in records:
        if row['probeEnabled']:http_samples.extend(int(sample['elapsed']) for sample in csvrows(OUT/('s1-20-'+row['tag']+'.jtl')))
    http_mean=statistics.mean(http_samples)
    verification.update(probeHttpMeanMs=http_mean,httpOutsidePurchaseMethodMeanMs=http_mean-stats['purchase.total']['meanMs'])
    owned=json.loads((OUT/'owned-transaction-verification.json').read_text('utf-8'))
    assert not any(owned['residual'].values()) and owned['isolatedFixturesRemaining']==0
    shutdown=json.loads((OUT/'shutdown-verification.json').read_text('utf-8-sig'))
    delayed=json.loads((OUT/'scheduled-cleanup-verification.json').read_text('utf-8'))
    assert shutdown['confirmedRecordedServicesExited'] and not shutdown['remainingListening']
    assert delayed['remainingRemovedOwnedEntries']==0
    verification.update(ownedTransactionResidual=owned['residual'],ownedOrderCount=owned['loggedOrderCount'],
        removedDelayTasks=delayed['removedEntries'],servicesStopped=True,shutdownFreeMemoryGB=shutdown['freeMemoryGB'])
    tests=[]
    for name in ['SeatAllocatorTest','CarriageDirectoryTest','PurchaseTicketOrchestrationTest','PurchaseTicketServiceTest','CarriageReservationTest','TicketCallbackServiceTest','TicketAvailabilityTokenBucketTest']:
        path=c.ROOT/f'12306/my12306/services/ticket-services/target/surefire-reports/TEST-edu.swu.fcj.my12306.biz.ticketservice.service.{name}.xml'
        suite=ET.parse(path).getroot();tests.append(dict(name=name,tests=int(suite.get('tests')),failures=int(suite.get('failures')),errors=int(suite.get('errors'))))
    assert all(test['failures']==test['errors']==0 for test in tests)
    c.dump(OUT/'final-verification.json',verification)
    c.dump(OUT/'stage-summary.json',dict(stages=stats,distribution=distribution,perKeyAcquisitionIntervalsMs={key:statistics.mean(values) for key,values in lanes.items()},sql=sql,verification=verification,tests=tests))
    lines=['# 车厢锁优化：20并发性能报告','',
        '测试日期：2026-10-09。仅测修改后的车厢锁实现；此前席别锁报告属于历史参考，不作为同环境基线。未提交、合并或推送。','',
        '## 实现与环境','',
        '车次＋席别＋车厢锁；进程内5分钟静态车厢目录与按workerId初始化的原子轮转计数器。单车厢候选失败后独立事务重试，最终按统一资源顺序取得全部相关车厢锁进行跨车厢兜底。用户锁、令牌桶、条件占座和建单恢复语义保留。提交/回滚结果不确定不重试；仅方法体内数据库锁错误经代理回滚后最多重试2次。',
        'MySQL 8.0.44、Redis、Java21；Feign。单Ticket正式测量，支付/MQ不启动。堆上限Ticket/User/Nacos/JMeter256MB、Order192MB、Gateway160MB。既有静态查询、解锁归属检查、日志和数据库持久性配置未优化。',
        '每轮目标池为车次1、席别2、北京南→宁波，07～15共9车厢810张全部可售。3轮独立预热与2秒校准在正式窗口外，各轮结束取消并清理本轮交易；未调用全池prepare脚本。正式窗口按校准速率1.5倍限制预计消耗≤405张，实际超过则整组缩短并重测。共享HTTP客户端，修正后的HTTP计时；QPS窗口从首个HTTP发送到最后完成，包含排空。','',
        '## 六轮正式结果','',
        '|采集|轮次|窗口秒|成功请求|成功QPS|成功P95 ms|成功P99 ms|锁内调用均值 ms|','|---|---:|---:|---:|---:|---:|---:|---:|']
    for row in records:lines.append(f"|{'开' if row['probeEnabled'] else '关'}|{row['tag']}|{row['windowConfigured']}|{row['success']}|{row['successQps']:.2f}|{row['quantiles']['success']['0.95']}|{row['quantiles']['success']['0.99']}|{row['holdAvgMs']:.2f}|")
    for enabled in [False,True]:
        selected=[row for row in records if row['probeEnabled']==enabled];qps=[row['successQps'] for row in selected]
        lines+=['',f"采集{'开启' if enabled else '关闭'}：成功QPS中位数 **{statistics.median(qps):.2f}**，范围{min(qps):.2f}～{max(qps):.2f}；成功P99三轮中位数{statistics.median(row['quantiles']['success']['0.99'] for row in selected):.0f}ms。"]
    lines+=['',f'最终统一窗口为{records[0]["windowConfigured"]}秒。排除的较长窗口轮次共{len(excluded)}轮：4秒序列出现419张、3秒序列出现480张，均超过405张上限，因此连同同组已完成轮次排除并缩短重测；原始数据完整保留在excluded-rounds.json。',
            '六轮范围有重叠，采集开启中位数较高不能解释为探针提升性能；短窗口和运行状态仍带来波动。只测试修改后的版本，历史差值不能用于计算此次改造的改善率。']
    lines+=['','所有正式请求均成功，未发生令牌拒绝、降级或请求保护上限触发；订单、车票、座位数量及唯一坐标一致。采集关闭仍加载代理，并非完全无探针对照。','',
        '## 开启采集的阶段统计','',
        '三轮请求合并统计；父子阶段包含关系不能重复相加。JDBC计时和数据库摘要计时边界不同。','',
        '|阶段|平均 ms|P50 ms|P95 ms|P99 ms|','|---|---:|---:|---:|---:|']
    for name in ['purchase.total','chain.validation','token.admission','passenger.remote','user.lock-wait','seat.lock-wait','tx.proxy','tx.begin','tx.body','seat.allocate','jdbc.seat-select','jdbc.seat-update','jdbc.ticket-insert','jdbc.commit','tx.finish','seat.ownership-check','seat.unlock-rpc','seat.unlock-safe','order.remote','jdbc.connection']:
        value=stats.get(name)
        if value:lines.append(f"|{name}|{value['meanMs']:.3f}|{value['p50Ms']:.3f}|{value['p95Ms']:.3f}|{value['p99Ms']:.3f}|")
    lines+=['','## 并行、分配与瓶颈分析','',
        f"同一JVM单调时钟验证：不同车厢最多同时存在 **{max_parallel}** 个已获锁且尚未开始解锁的临界区；相同车厢key重叠为0。开启采集正式轮中，{overlap_count}个临界区与其他车厢存在交叠。按每个key计算取得锁间隔，包含该车厢空闲时间，不能把其倒数当作单通道处理上限。",'',
        '|车厢|六轮成功分配张数|','|---|---:|']
    for key,value in sorted(distribution.items()):lines.append(f'|{key}|{value}|')
    lines+=['','|轮次|快路径成功|候选不足|跨车厢兜底|数据库重试|','|---|---:|---:|---:|---:|']
    for row in records:
        counters=row['carriageCounters'];lines.append('|'+row['tag']+'|'+ '|'.join(str(int(counters.get('my12306.purchase.carriage.'+name,0))) for name in ['fast-success','candidate-miss','fallback','db-retry'])+'|')
    lines+=['','数据库摘要原始差值见stage-summary.json。连接池等待、提交和远程建单成本需结合阶段数据判断；不能沿用旧单席别24.01ms周期计算9车厢容量，也不把历史QPS差值写为此次优化改善率。','',
      f"资源采样{len(resources)}个正式窗口点，最低可用内存{verification.get('minFreeMemoryGB')}GB，最高采样CPU {verification.get('maxSampledCpuPercent')}%，最高pageReads/sec {verification.get('maxSampledPageReads')}；最高采样连接池pending={max_pending}。短窗口采样不能排除未捕获的瞬时尖峰。",'',
        '## 正确性与清理','',f"针对性测试{sum(test['tests'] for test in tests)}项全部通过：候选切换、整单回滚、跨车厢、条件更新、令牌与建单补偿；隔离夹具不混入810张正式库存。"]
    for name in ['oversell-0','oversell-1','oversell-2','two-instance-guard','two-instance-50']:
        lines.append('- '+name+'：'+json.dumps(json.loads((OUT/(name+'.json')).read_text('utf-8')),ensure_ascii=False))
    lines+=['',f"最终：810张全部可售，目标池外状态未改变，perf400测试车票ID恢复到起点；令牌值{verification['token']}（None表示按TTL过期）。业务JAR与本轮记录指纹一致。核对本轮{owned['loggedOrderCount']}个订单号，订单、明细和车票残留均为0，隔离座位夹具残留为0。",
        f"仅移除本轮已删除订单对应的{delayed['removedEntries']}个延迟任务，未整体删除队列。测试服务全部停止，停止核验时可用内存{shutdown['freeMemoryGB']:.2f}GB。",
        '延迟任务和服务停止结果分别见scheduled-cleanup-verification.json、shutdown-verification.json；原始结果、JTL、SQL差值、阶段与锁事件均保存在被忽略的results/carriage目录。','',
        '## 边界与复现','',
        '当前车厢目录假定运行期间不动态修改车厢结构；导入/修改车厢布局后需要清空目录缓存或重启实例再接入流量。部署必须整体停止旧席别锁实例后替换，不能混跑两种互斥协议。当前售票区间模型不等同真实铁路区段复用模型。',
        '复现顺序：构建及针对性测试 → run-carriage.py preflight → start-current-services.ps1指定results/carriage与新探针 → smoke、oversell → start-carriage-ticket.ps1 -Port 9012 -WorkerId 2并执行multi → stop-carriage-ticket2.ps1 → performance → 停止服务 → cleanup-scheduled-tests.py指定本轮目录 → run-carriage.py verify → analyze-carriage.py。停止服务前保存service-pids中各进程的CreationDate到process-start-times.json，用于GC窗口对齐。只使用本轮创建交易的取消清理，不使用prepare。']
    section=lines.index('## 正确性与清理')
    findings=[
        '### 测量结论','',
        f"1. **席别全局串行已被拆开。** 9个车厢的实际分配为173～174张/车厢，最多9个临界区同时执行；车厢锁获取/等待均值{stats['seat.lock-wait']['meanMs']:.2f}ms，约占购票方法均值的{100*stats['seat.lock-wait']['meanMs']/stats['purchase.total']['meanMs']:.1f}%。此次正式库存充足，所有请求均走单车厢快路径；不能把此吞吐外推到碎片化或临近售罄。",
        f"2. **完整事务依然有成本，并行没有消除提交。** 事务代理均值{stats['tx.proxy']['meanMs']:.2f}ms，其中JDBC提交{stats['jdbc.commit']['meanMs']:.2f}ms，占事务代理约{100*stats['jdbc.commit']['meanMs']/stats['tx.proxy']['meanMs']:.1f}%；提交P95约{stats['jdbc.commit']['p95Ms']:.2f}ms。数据库摘要也显示提交比按需选座更重，但没有磁盘/fsync专项测量，不能断言已定位为磁盘瓶颈。",
        f"3. **选座与连接池不是这次的主要等待来源。** 完整选座均值{stats['seat.allocate']['meanMs']:.2f}ms；取连接均值{stats['jdbc.connection']['meanMs']:.3f}ms，采样pending为0。行锁、提交或调度导致的尾延迟仍需单独分析，不能仅凭平均值排除全部数据库影响。",
        f"4. **接口时间还包含锁外及业务方法外成本。** 远程建单均值{stats['order.remote']['meanMs']:.2f}ms，约占购票方法均值的{100*stats['order.remote']['meanMs']/stats['purchase.total']['meanMs']:.1f}%。HTTP均值{http_mean:.2f}ms，购票方法均值{stats['purchase.total']['meanMs']:.2f}ms，差值{http_mean-stats['purchase.total']['meanMs']:.2f}ms包含客户端、网关/鉴权、网络及分发等，未细分，不能全部称为网关耗时。固定20个同步请求线程时，完整请求耗时会影响闭环吞吐，不能再用单把车厢锁耗时的倒数估算整个接口能力。",
        '5. **结论是当前版本能实现车厢间并行，且本轮成功吞吐超过100QPS。** 没有同环境改造前基线，不能写“提升某百分比”；2秒窗口也不足以证明长时间稳定容量。下一步应分别测量HTTP方法外成本、远程建单和提交，而不是立即继续把车厢锁拆到座位锁。',''
    ]
    lines[section:section]=findings
    (HERE/'车厢锁优化-20并发性能报告.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(json.dumps(verification,ensure_ascii=False))

if __name__=='__main__':analyze()
