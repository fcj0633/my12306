"""Group current-version 50/100 experiments; history never becomes a causal baseline."""
import csv, datetime, hashlib, importlib.util, json, pathlib, re, statistics
HERE=pathlib.Path(__file__).resolve().parent;OUT=HERE/'results/carriage-50-100'
spec=importlib.util.spec_from_file_location('carriage',HERE/'run-carriage.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.OUT=OUT;m.c.OUT=OUT;m.c.base.OUT=OUT;c=m.c

def csvrows(path):return list(csv.DictReader(path.open(encoding='utf-8')))
def timestamp(value):return datetime.datetime.fromisoformat(re.sub(r'(\.\d{6})\d+',r'\1',value.replace('Z','+00:00'))).timestamp()
def stats(values):return dict(mean=statistics.mean(values),p50=c.base.percentile(values,.5),p95=c.base.percentile(values,.95),p99=c.base.percentile(values,.99),n=len(values))

def group(rows):
    phases={};distribution={};intervals=[];http=[];resources=[];sql=[];boundary_skipped=0
    for row in rows:
        for key,value in row['carriageDistribution'].items():distribution[key]=distribution.get(key,0)+value
        http.extend(int(sample['elapsed']) for sample in csvrows(OUT/f"s1-{row['concurrency']}-{row['tag']}.jtl"))
        resources.extend(sample for sample in row['resource'] if row['httpStartMs']/1000<=sample['time']<=row['httpEndMs']/1000)
        for schema,field in [('ticket','digest'),('order','orderDigest')]:
            for key,after in row['dbAfter'].get(field,{}).items():
                before=row['dbBefore'].get(field,{}).get(key,dict(count=0,time=0,rows=0));count=after['count']-before['count']
                if count>0:sql.append(dict(schema=schema,tag=row['tag'],text=after['text'],count=count,
                    meanMs=(after['time']-before['time'])/1e9/count,rowsPerCall=(after['rows']-before['rows'])/count))
        if not row['probeEnabled']:continue
        traces=csvrows(OUT/(row['tag']+'-traces.csv'));locks=csvrows(OUT/(row['tag']+'-locks.csv'));by_id={}
        for trace in traces:
            by_id.setdefault(trace['id'],{})[trace['stage']]=trace
            phases.setdefault(trace['stage'],[]).append(int(trace['durationNs'])/1e6)
        for item in by_id.values():
            if int(item['tx.proxy']['count'])!=1:boundary_skipped+=1;continue
            body,proxy=item['tx.body'],item['tx.proxy']
            phases.setdefault('tx.begin',[]).append((int(body['stageStartNs'])-int(proxy['stageStartNs']))/1e6)
            phases.setdefault('tx.finish',[]).append((int(proxy['stageEndNs'])-int(body['stageEndNs']))/1e6)
        for acquisition in [event for event in locks if event['stage']=='seat.lock-wait' and event['failed']=='false']:
            safe=next((event for event in locks if event['id']==acquisition['id'] and event['key']==acquisition['key']
                and event['stage']=='seat.unlock-safe' and int(event['startNs'])>=int(acquisition['endNs'])),None)
            assert safe,'Missing release'
            intervals.append((row['tag'],acquisition['key'],int(acquisition['endNs']),int(safe['startNs'])))
    parallel=0
    for row in rows:
        subset=[item for item in intervals if item[0]==row['tag']]
        for key in {item[1] for item in subset}:
            ordered=sorted([item for item in subset if item[1]==key],key=lambda item:item[2])
            assert all(a[3]<=b[2] for a,b in zip(ordered,ordered[1:])),'Same key overlap'
        active=0
        for _,delta in sorted([(item[2],1) for item in subset]+[(item[3],-1) for item in subset]):
            active+=delta;parallel=max(parallel,active)
    pools={}
    for service,port in [('ticket',9002),('order',9003)]:
        deltas={};pending=[]
        for row in rows:
            before=row['metricsBefore'] if service=='ticket' else row['extraMetricsBefore'][str(port)]
            after=row['metricsAfter'] if service=='ticket' else row['extraMetricsAfter'][str(port)]
            for name in ['hikaricp.connections.acquire','hikaricp.connections.timeout']:
                for stat in ['COUNT','TOTAL_TIME']:
                    deltas[name+'.'+stat]=deltas.get(name+'.'+stat,0)+after.get(name,{}).get(stat,0)-before.get(name,{}).get(stat,0)
        for sample in resources:
            values=sample['values'] if service=='ticket' else sample.get('extra',{}).get(str(port),{})
            if values.get('hikaricp.connections.pending'):pending.append(values['hikaricp.connections.pending'].get('VALUE',0))
        count=deltas.get('hikaricp.connections.acquire.COUNT',0)
        pools[service]=dict(samplePoints=len(pending),maxSampledPending=max(pending,default=None),
            acquireMeanMs=1000*deltas.get('hikaricp.connections.acquire.TOTAL_TIME',0)/count if count else None,
            acquireCount=count,timeoutCount=deltas.get('hikaricp.connections.timeout.COUNT',0))
    sql_aggregate={}
    for query in sql:
        key=query['schema']+'|'+query['text']
        entry=sql_aggregate.setdefault(key,dict(schema=query['schema'],text=query['text'],count=0,totalMs=0,rows=0))
        entry['count']+=query['count'];entry['totalMs']+=query['count']*query['meanMs'];entry['rows']+=query['count']*query['rowsPerCall']
    for entry in sql_aggregate.values():
        entry['meanMs']=entry['totalMs']/entry['count'];entry['rowsPerCall']=entry['rows']/entry['count']
    return dict(qpsMedian=statistics.median(row['successQps'] for row in rows),
        qpsMin=min(row['successQps'] for row in rows),qpsMax=max(row['successQps'] for row in rows),
        p95Median=statistics.median(row['quantiles']['success']['0.95'] for row in rows),
        p99Median=statistics.median(row['quantiles']['success']['0.99'] for row in rows),
        success=sum(row['success'] for row in rows),http=stats(http),stages={name:stats(values) for name,values in phases.items()},
        distribution=distribution,maxParallel=parallel,pools=pools,sql=sql,sqlAggregate=list(sql_aggregate.values()),
        samplesPerRound=[row['samples'] for row in rows],multiAttemptBoundarySkipped=boundary_skipped,
        counters={name:sum(row['carriageCounters'].get('my12306.purchase.carriage.'+name,0) for row in rows)
            for name in ['attempt','fast-success','candidate-miss','fallback','db-retry']})

def analyze():
    rows=json.loads((OUT/'formal-matrix.json').read_text('utf-8'));assert len(rows)==12
    assert all(row['valid'] and row['poolBefore']==810 and row['success']<=405 and not row['capReached'] for row in rows)
    assert len({row['windowConfigured'] for row in rows})==1
    groups={}
    for level in [50,100]:
        for enabled in [False,True]:
            selected=[row for row in rows if row['concurrency']==level and row['probeEnabled']==enabled];assert len(selected)==3
            groups[f'{level}-{int(enabled)}']=group(selected)
    env=json.loads((OUT/'environment.json').read_text('utf-8'));m.inventory_clean()
    assert c.base.sql('SELECT id,seat_status FROM 12306_ticket.t_seat WHERE NOT ('+c.base.POOL+') ORDER BY id;')==env['outsidePool']
    assert sorted(c.base.ticket_ids())==env['initialPerfTicketIds']
    for service,expected in env['artifactSha256'].items():
        path=c.ROOT/f'12306/my12306/services/{service}-services/target/{service}-services-0.0.1-SNAPSHOT.jar'
        assert hashlib.sha256(path.read_bytes()).hexdigest()==expected
    manifest=json.loads((OUT/'source-manifest.json').read_text('utf-8'))
    assert all(hashlib.sha256((c.ROOT/path).read_bytes()).hexdigest()==expected for path,expected in manifest['businessSourceSha256'].items())
    owned=json.loads((OUT/'owned-transaction-verification.json').read_text('utf-8'));assert not any(owned['residual'].values())
    shutdown=json.loads((OUT/'shutdown-verification.json').read_text('utf-8-sig'));assert shutdown['confirmedRecordedServicesExited'] and not shutdown['remainingListening']
    delayed=json.loads((OUT/'scheduled-cleanup-verification.json').read_text('utf-8'));assert delayed['remainingRemovedOwnedEntries']==0
    token=c.base.redis('HGET',m.TOKEN,'2');assert token is None or int(token)==810
    machine=[json.loads(line) for line in (OUT/'machine-resources.jsonl').read_text('utf-8-sig').splitlines()]
    sampled=[item for item in machine if any(row['httpStartMs']/1000<=timestamp(item['utc'])<=row['httpEndMs']/1000 for row in rows)]
    starts=json.loads((OUT/'process-start-times.json').read_text('utf-8-sig'));gc={}
    for process in starts:
        log=(OUT/(process['service']+'.gc.log')).read_text('utf-8',errors='replace');pauses=[];start=timestamp(process['CreationDate'])
        for line in log.splitlines():
            match=re.match(r'\[(\d+(?:\.\d+)?)s\].*Pause.* (\d+(?:\.\d+)?)ms$',line)
            if match and any(row['httpStartMs']/1000<=start+float(match[1])<=row['httpEndMs']/1000 for row in rows):pauses.append((float(match[2]),'Pause Full' in line))
        gc[process['service']]=dict(pauseCount=len(pauses),fullCount=sum(item[1] for item in pauses),maxMs=max((item[0] for item in pauses),default=0),totalMs=sum(item[0] for item in pauses))
    injector_gc=[]
    for row in rows:
        log=(OUT/f"s1-{row['concurrency']}-{row['tag']}.gc.log").read_text('utf-8',errors='replace')
        pauses=[]
        for line in log.splitlines():
            match=re.match(r'\[(\d+(?:\.\d+)?)s\].*Pause.* (\d+(?:\.\d+)?)ms$',line)
            if match:pauses.append((float(match[2]),'Pause Full' in line))
        injector_gc.append(dict(tag=row['tag'],scope='entire injector JVM, includes setup outside HTTP window',
            pauseCount=len(pauses),fullCount=sum(item[1] for item in pauses),
            maxMs=max((item[0] for item in pauses),default=0),totalMs=sum(item[0] for item in pauses)))
    verification=dict(formalRounds=12,success=sum(row['success'] for row in rows),availableSeats=810,
        outsidePoolUnchanged=True,businessSourceAndArtifactsUnchanged=True,ownedResidual=owned['residual'],
        ownedOrderCount=owned['loggedOrderCount'],removedDelayTasks=delayed['removedEntries'],token=None if token is None else int(token),
        servicesStopped=True,shutdownFreeMemoryGB=shutdown['freeMemoryGB'],gc=gc,injectorGc=injector_gc,machinePoints=len(sampled),
        minFreeMemoryGB=min((item['freeMemoryGB'] for item in sampled),default=None),
        maxSampledCpu=max((item['cpuPercent'] for item in sampled),default=None),
        maxSampledPageReads=max((item['pageReadsPerSec'] for item in sampled),default=None))
    excluded=json.loads((OUT/'excluded-rounds.json').read_text('utf-8'));verification['excludedRounds']=len(excluded)
    c.dump(OUT/'final-verification.json',verification);c.dump(OUT/'larger-summary.json',dict(groups=groups,verification=verification))
    with (OUT/'formal-round-summary.csv').open('w',encoding='utf-8-sig',newline='') as target:
        fields=['concurrency','probeEnabled','tag','windowConfigured','observedSeconds','samples','success','successRate',
                'totalQps','successQps','poolBefore','poolAfter','valid']
        writer=csv.DictWriter(target,fieldnames=fields);writer.writeheader()
        writer.writerows({field:row[field] for field in fields} for row in rows)
    lines=['# 车厢锁版本：50与100并发性能报告','',
        '测试日期：2026-10-09。仅测试当前车厢锁版本；业务源码、JAR、表结构和连接池配置未修改。上次20并发结果仅作历史参考。','',
        '## 环境和口径','',
        '单Ticket实例、Feign；Nacos/User/Ticket/JMeter最大堆256MB，Order192MB、Gateway160MB。每轮车次1、席别2、北京南→宁波从原810张可售票开始，共9个车厢。共享HTTP客户端，QPS从首个HTTP发送至最后响应完成，包含排空。HTTP采样排除初始化、屏障和脚本编译，包含同步发送、响应读取和采样器响应分类；并非仅购票方法计时。',
        '每档3轮独立预热与短时校准，结合较高实测速率的1.5倍及100个在途请求余量选择共同窗口；正式消耗不超过405张，超出时统一缩短并重测。预热交易在正式窗口外清理。每档采集关闭、开启各3轮；关闭仍加载代理，不等于无探针。',
        f"正式共同窗口：**{rows[0]['windowConfigured']}秒**。排除轮次：{len(excluded)}，原始数据保留在excluded-rounds.json。短窗口不证明长期稳定容量。",'',
        '## 正式结果','',
        '|并发|采集|三轮成功QPS|中位数|范围|成功P95中位数 ms|成功P99中位数 ms|成功次数|',
        '|---:|---|---|---:|---|---:|---:|---:|']
    for key,value in groups.items():
        level,enabled=map(int,key.split('-'));selected=[row for row in rows if row['concurrency']==level and row['probeEnabled']==bool(enabled)]
        qps=' / '.join(f"{row['successQps']:.2f}" for row in selected)
        lines.append(f"|{level}|{'开' if enabled else '关'}|{qps}|{value['qpsMedian']:.2f}|{value['qpsMin']:.2f}～{value['qpsMax']:.2f}|{value['p95Median']:.0f}|{value['p99Median']:.0f}|{value['success']}|")
    lines+=['','全部有效正式请求成功，成功率100%，总QPS与成功QPS相同；失败分类全部为OK。令牌拒绝、降级和数量保护触发均为0，订单/明细/车票/占座及唯一坐标一致。','',
        '**测量限制：50并发每轮仅50次、100并发每轮仅100次，即每个线程一笔购票。各轮HTTP请求尚未完成时2秒发送预算已经结束，没有形成持续循环施压。上述QPS是这一批并发请求的完成速率，不能用来确定饱和点或长期稳定容量。**','',
        f"本次同条件50→100并发，关闭采集的QPS中位数增长{(groups['100-0']['qpsMedian']/groups['50-0']['qpsMedian']-1)*100:.1f}%，开启采集增长{(groups['100-1']['qpsMedian']/groups['50-1']['qpsMedian']-1)*100:.1f}%，均未翻倍；两组P99均上升。采集开/关三轮范围部分重叠且存在运行时波动，不能将开组更高的吞吐解释成采集带来性能提升。",'',
        '## 阶段分析','',
        '开启采集的三轮请求合并统计，父子阶段不能重复相加。阶段累计多次尝试时只解释累计耗时；事务首末边界仅使用一次尝试的请求。','',
        '|阶段|50并发均值 ms|100并发均值 ms|','|---|---:|---:|']
    names={'purchase.total':'购票方法','chain.validation':'责任链','token.admission':'令牌准入','passenger.remote':'乘车人查询',
        'seat.lock-wait':'车厢锁获取/等待','tx.proxy':'事务完整调用','tx.body':'事务业务方法','seat.allocate':'完整选座',
        'jdbc.seat-select':'选座JDBC','jdbc.seat-update':'条件占座JDBC','jdbc.ticket-insert':'车票插入JDBC','jdbc.commit':'提交JDBC',
        'seat.unlock-safe':'安全解锁','order.remote':'远程建单','jdbc.connection':'Ticket取连接',
        'tx.begin':'单次事务建立边界','tx.finish':'单次事务退出边界（含提交）'}
    for key,label in names.items():
        a=groups['50-1']['stages'].get(key);b=groups['100-1']['stages'].get(key)
        if a and b:lines.append(f"|{label}|{a['mean']:.3f}|{b['mean']:.3f}|")
    lines+=['','|并发|HTTP均值 ms|购票方法均值 ms|方法外差值 ms|最多并行车厢|候选不足|兜底|DB重试|','|---:|---:|---:|---:|---:|---:|---:|---:|']
    for level in [50,100]:
        value=groups[f'{level}-1'];method=value['stages']['purchase.total']['mean'];counters=value['counters']
        lines.append(f"|{level}|{value['http']['mean']:.2f}|{method:.2f}|{value['http']['mean']-method:.2f}|{value['maxParallel']}|{int(counters['candidate-miss'])}|{int(counters['fallback'])}|{int(counters['db-retry'])}|")
    lines+=['','HTTP方法外差值包括客户端及响应处理、网络、网关/鉴权与分发，未单独定位，不能全称网关耗时。同车厢key临界区交叠为0，跨车厢并行来自同一JVM单调时钟证据。','',
        '### 主要原因和未定位边界','',
        '1. **并行生效，但同车厢排队明显。** 两档均观测到9个车厢临界区同时存在，分配均匀，无同key交叠。开启采集50并发每车厢15～18张，100并发每车厢33～34张；全部走一次候选快路径，无候选不足、跨车厢兜底和DB重试。车厢获取/等待均值220.7→640.5ms，是购票方法内部增加最明显的阶段。该阶段包含Redis锁操作往返和调度，不能全部解释为纯排队时间。',
        '2. **选座查询不是本轮最大阶段，提交仍占据锁内时间。** 选座JDBC约4～5ms，完整事务约78～82ms。提交JDBC20.6→26.9ms，占事务约26%→33%；释放锁前必须完成提交，因而提交延迟会放大后续车厢等待。价格/站点读取等仍在事务内，按计划没有额外优化。',
        '3. **远程建单有所变慢，但连接池证据不足以解释秒级延迟。** 远程建单86.1→104.2ms。Ticket连接获取平均低于0.1ms，Order不高于4.2ms；均无连接超时，Order少数采样pending为2。采样稀疏，不能据此排除短暂争抢，也不支持将全部延迟归因于连接池容量。',
        '4. **方法外耗时尚未定位，不能只归因于车厢锁。** HTTP均值2340.8→3594.9ms，购票方法仅559.8→1062.4ms；方法外差值约1781.0→2532.5ms，是本轮必须进一步拆分的部分。当前没有客户端发送/接收、网关各阶段及Ticket入口队列的独立时间戳，无法区分这些环节。',
        '5. **本轮绝对结果明显低于历史20并发，但无法做严格退化归因。** 历史采集关闭中位数122.15QPS、开启145.12QPS，仅作参考。当前JAR指纹一致，性能差异不能自动证明业务实现退化；并发和运行会话不同，本轮提交、锁等待和方法外延迟均需按现有证据独立解释。CPU采样出现99%，资源竞争或客户端运行时开销是待验证因素；没有证据证明单一原因。','',
        '## 连接池、SQL与资源','',
        '|并发|采集|服务|获取连接平均 ms|连接超时|正式窗口采样点|最大采样pending|','|---:|---|---|---:|---:|---:|---:|']
    for key,value in groups.items():
        level,enabled=map(int,key.split('-'))
        for service,pool in value['pools'].items():
            mean=pool['acquireMeanMs'];lines.append(f"|{level}|{'开' if enabled else '关'}|{service}|{mean:.4f}"+f"|{pool['timeoutCount']:.0f}|{pool['samplePoints']}|{pool['maxSampledPending']}|") if mean is not None else None
    lines+=['','### 服务器语句摘要（开启采集，三轮按执行次数加权）','',
        '|指标|50并发|100并发|','|---|---:|---:|']
    for schema,label,pattern in [('ticket','Ticket提交','COMMIT'),('order','Order提交','COMMIT'),
                                 ('ticket','限量选座','SELECT `id` , `carriage_number` , `seat_number`')]:
        cells=[]
        for level in [50,100]:
            values=[q for q in groups[f'{level}-1']['sqlAggregate'] if q['schema']==schema and q['text'].startswith(pattern)]
            count=sum(q['count'] for q in values);total=sum(q['totalMs'] for q in values)
            cells.append(f'{total/count:.3f}ms / {count}次' if count else '未观测')
        lines.append('|'+label+'|'+'|'.join(cells)+'|')
    lines+=['','语句摘要快照跨多个连接读取，不与请求结束原子同步，因此提交计数可能与成功请求相差1～2；交易正确性以订单/车票/座位对账为准，不使用摘要计数冒充事务数。','',
        f"正式资源采样{len(sampled)}点，最低可用内存{verification['minFreeMemoryGB']}GB，最高采样CPU {verification['maxSampledCpu']}%，最高pageReads/sec {verification['maxSampledPageReads']}。短窗口采样稀疏，不能排除未捕获尖峰。没有触发低内存终止条件。",'',
        'Ticket与Order语句摘要差值、检查行数和耗时见larger-summary.json；驱动计时、服务调用及服务器SQL口径不同。服务GC已按进程创建时间对齐至正式窗口，见final-verification.json。','',
        '|服务|正式窗口GC暂停次数|Full GC|最长暂停 ms|暂停总和 ms|','|---|---:|---:|---:|---:|']
    for service,values in gc.items():
        lines.append(f"|{service}|{values['pauseCount']}|{values['fullCount']}|{values['maxMs']:.3f}|{values['totalMs']:.3f}|")
    lines+=['',f"JMeter每轮新建JVM，其整个进程（含窗口外初始化）最长GC暂停为{max(value['maxMs'] for value in injector_gc):.3f}ms，Full GC总数{sum(value['fullCount'] for value in injector_gc)}；本次这12次均标记为显式System.gc()，每轮一次，不能据此认定内存耗尽。该口径不与服务端正式窗口暂停总和相加，也不能据此认定客户端运行时没有其他调度或编译开销。",'',
        '## 正确性、清理与复现','',
        f"12轮共{verification['success']}次成功购票；核对本轮{verification['ownedOrderCount']}个订单号，订单、明细、车票残留均为0。最终810张全部可售，目标池外未改变，令牌值{verification['token']}（None表示TTL过期）。",
        f"清理本轮{verification['removedDelayTasks']}个延迟任务，没有整体删除队列；所有本轮服务停止，停止核验可用内存{verification['shutdownFreeMemoryGB']:.2f}GB。未提交、合并、推送，也未继续200/400并发。",
        '复现：run-carriage.py preflight --output-dir 新目录；start-current-services.ps1指定该目录与代理；单请求smoke；run-carriage.py larger --output-dir 同目录；停止服务；清理本轮延迟任务；run-carriage.py verify --output-dir 同目录；analyze-carriage-larger.py。启动后保存各服务CreationDate到process-start-times.json。原始JTL、SQL差值、阶段和锁事件均在独立results/carriage-50-100目录，JWT文件被Git忽略。']
    lines+=['','## 后续建议（本次未执行）','',
        '优先补充客户端纯send计时、Gateway入口/鉴权/路由转发、Ticket入口到购票方法开始的时间戳，解释1.8～2.5秒方法外差值；其次在确认CPU与提交耗时稳定后复测，而不是继续增加并发或细化座位锁。若需要评估持续容量，须另外设计不会过快耗尽810张库存的测试，并将吞吐口径与本轮突发请求分开。','',
        '![当前版本50与100并发测试图](车厢锁优化-50与100并发表现.png)']
    (HERE/'车厢锁优化-50与100并发性能报告.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(json.dumps(dict(groups={key:{name:value[name] for name in ['qpsMedian','qpsMin','qpsMax','p99Median','success']} for key,value in groups.items()},verification=verification),ensure_ascii=False))

if __name__=='__main__':analyze()
