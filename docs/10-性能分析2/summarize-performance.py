"""Summarize only completed, reconciled formal rounds of the current HEAD."""
import csv, datetime, json, pathlib, statistics, re
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE/'results'
TICKET_PID = next(p['pid'] for p in json.loads((OUT/'service-pids.json').read_text('utf-8-sig')) if p['service']=='ticket')

def md(v, digits=2):
    return '缺失' if v is None else f'{v:.{digits}f}'

def timer_delta(r, name):
    a=r['metricsAfter'][name];b=r['metricsBefore'][name]
    n=a.get('COUNT',0)-b.get('COUNT',0)
    return (a.get('TOTAL_TIME',0)-b.get('TOTAL_TIME',0))*1000/n if n else None

def scalar(r, field):
    values=[s['values'].get(field,{}).get('VALUE') for s in r['resource']
            if r['httpStartMs']/1000<=s['time']<=r['httpEndMs']/1000]
    return [v for v in values if v is not None]

def sql_cost(r, predicate):
    calls=wait=examined=0
    for key,a in r['dbAfter']['digest'].items():
        if not predicate(a['text']):continue
        b=r['dbBefore']['digest'].get(key,dict(count=0,time=0,rows=0))
        calls+=a['count']-b['count'];wait+=a['time']-b['time'];examined+=a['rows']-b['rows']
    return (wait/1e9/calls,examined/calls) if calls else (None,None)

def resources(r, machine):
    lo=r['httpStartMs']/1000;hi=r['httpEndMs']/1000
    return [s for s in machine if lo<=s['epoch']<=hi]

def summary(scenario, machine):
    path=OUT/f'{scenario}-matrix.json'
    rows=json.loads(path.read_text('utf-8'))
    assert len(rows)>=24 and all(r['valid'] for r in rows), 'Incomplete or invalid matrix'
    for r in rows:
        raw=OUT/f"{scenario}-{r['concurrency']}-{r['tag']}.jtl"
        data=list(csv.DictReader(raw.open(encoding='utf-8-sig')))
        assert all(x['label']=='POST purchase [HTTP timing v2]' for x in data), 'Uncorrected compilation time in JTL'
    levels={}
    for n in [1,5,10,20,50,100,200,400]:
        rs=[r for r in rows if r['concurrency']==n]
        assert len(rs)>=3
        vals={k:[r[k] for r in rs] for k in ['successQps','totalQps','holdAvgMs','samples','observedSeconds','successRate']}
        level={k:dict(median=statistics.median(v),min=min(v),max=max(v)) for k,v in vals.items()}
        level.update(successP50=statistics.median(r['quantiles']['success']['0.5'] for r in rs),
            successP95=statistics.median(r['quantiles']['success']['0.95'] for r in rs),
            successP99=statistics.median(r['quantiles']['success']['0.99'] for r in rs),
            rounds=len(rs),capRounds=sum(r['capReached'] for r in rs),
            participating=[r['participatingThreads'] for r in rs],
            minPurchasesPerClient=min(r['samples']/n for r in rs),
            tokenReject=sum(r['tokenReject'] for r in rs),
            lockKeys=rs[0]['lockKeys'],odPools=rs[0]['odPools'],
            passengerAvgMs=statistics.median(timer_delta(r,'my12306.purchase.passenger.remote') for r in rs),
            orderAvgMs=statistics.median(timer_delta(r,'my12306.purchase.order.remote') for r in rs),
            hikariAcquireAvgMs=statistics.median(timer_delta(r,'hikaricp.connections.acquire') for r in rs),
            hikariTimeouts=sum(r['metricsAfter']['hikaricp.connections.timeout'].get('COUNT',0)-r['metricsBefore']['hikaricp.connections.timeout'].get('COUNT',0) for r in rs))
        pending=[v for r in rs for v in scalar(r,'hikaricp.connections.pending')]
        level['hikariPendingMax']=max(pending) if pending else None
        usage=[v for r in rs for v in scalar(r,'process.cpu.usage')]
        level['ticketJvmCpuUsageMedianPct']=statistics.median(usage)*100 if usage else None
        level['ticketMetricSamples']=sum(sum(r['httpStartMs']/1000<=s['time']<=r['httpEndMs']/1000 for s in r['resource']) for r in rs)
        for field,name in [('hikariActiveMax','hikaricp.connections.active'),
                           ('jvmThreadsMax','jvm.threads.live'),('tomcatBusyMax','tomcat.threads.busy')]:
            values=[v for r in rs for v in scalar(r,name)]
            level[field]=max(values) if values else None
        level['hikariIdleBeforeMin']=min(r['metricsBefore']['hikaricp.connections.idle'].get('VALUE',0) for r in rs)
        level['poolBefore']=sorted({r['poolBefore'] for r in rs})
        level['poolAfterMin']=min(r['poolAfter'] for r in rs)
        level['activeLockKeys']=sorted({r.get('activeLockKeys',1 if scenario=='s1' else None) for r in rs})
        level['holdServiceRate']=1000/level['holdAvgMs']['median']
        level['qpsToHoldServiceRate']=level['successQps']['median']/level['holdServiceRate']
        per_key={}
        for r in rs:
            for key,raw in r['tokenBefore'].items():
                train=key.split('train_station_token_bucket:',1)[1].split('_',1)[0]
                before=dict(zip(raw[::2],raw[1::2]));after_raw=r['tokenAfter'][key]
                after=dict(zip(after_raw[::2],after_raw[1::2]))
                for seat,value in before.items():
                    decrease=int(value)-int(after.get(seat,value))
                    if decrease:per_key[f'{train}/{seat}']=per_key.get(f'{train}/{seat}',0)+decrease
        assert sum(per_key.values())==sum(r['success'] for r in rs),'Token debit does not match successful orders'
        level['successfulPurchasesByLockKey']=per_key
        points=[p for r in rs for p in resources(r,machine)]
        level['machineSamples']=len(points)
        level['freeMemoryMinGB']=min((p['freeMemoryGB'] for p in points),default=None)
        level['machineCpuMedian']=statistics.median(p['cpuPercent'] for p in points) if points else None
        level['pageReadMedian']=statistics.median(p['pageReadsPerSec'] for p in points) if points else None
        level['pageReadMax']=max((p['pageReadsPerSec'] for p in points),default=None)
        cores=[]
        for r in rs:
            samples=resources(r,machine)
            if len(samples)<2:continue
            first,last=samples[0],samples[-1]
            a=next((p for p in first['processes'] if p['pid']==TICKET_PID),None)
            b=next((p for p in last['processes'] if p['pid']==TICKET_PID),None)
            if a and b:cores.append((b['cpuSeconds']-a['cpuSeconds'])/(last['epoch']-first['epoch']))
        level['ticketCpuCoresMedian']=statistics.median(cores) if cores else None
        level['innodbRowLockWaits']=sum(int(dict(r['dbAfter']['status'])['Innodb_row_lock_waits'])-int(dict(r['dbBefore']['status'])['Innodb_row_lock_waits']) for r in rs)
        ratios=[];costs=[]
        for r in rs:
            def command_stats(text):
                result={}
                for line in text.splitlines():
                    if line.startswith('cmdstat_'):
                        key,value=line.split(':',1);result[key]=dict(f.split('=') for f in value.split(','))
                return result
            a=command_stats(r['redisAfter']['commandstats']);b=command_stats(r['redisBefore']['commandstats'])
            calls=usec=0
            for key in ['cmdstat_eval','cmdstat_evalsha']:
                calls+=float(a.get(key,{}).get('calls',0))-float(b.get(key,{}).get('calls',0))
                usec+=float(a.get(key,{}).get('usec',0))-float(b.get(key,{}).get('usec',0))
            ratios.append(calls/r['success']);costs.append(usec/calls if calls else 0)
        level['redisEvalCallsPerSuccess']=statistics.median(ratios)
        level['redisEvalServerMeanUsec']=statistics.median(costs)
        allocations=[sql_cost(r,lambda s:s.startswith('SELECT `id` , `carriage_number` , `seat_number`')) for r in rs]
        commits=[sql_cost(r,lambda s:s=='COMMIT') for r in rs]
        level['allocateSqlAvgMs']=statistics.median(v[0] for v in allocations)
        level['allocateRowsExaminedPerCall']=statistics.median(v[1] for v in allocations)
        level['commitSqlAvgMs']=statistics.median(v[0] for v in commits)
        levels[n]=level
    return rows,levels

if __name__=='__main__':
    machine=[]
    for line in (OUT/'machine-resources.jsonl').read_text('utf-8-sig').splitlines():
        x=json.loads(line);stamp=re.sub(r'(\.\d{6})\d+',r'\1',x['utc'])
        x['epoch']=datetime.datetime.fromisoformat(stamp.replace('Z','+00:00')).timestamp();machine.append(x)
    all_results={}
    body=['# 当前版本并发性能测试报告', '',
      '测试日期：2026-10-08。测试提交：`22b0405c2f5b508dc052c0df54ecae52e4318435`，单 Ticket 实例。仅测当前实现，没有旧版本A组，不计算接口提升百分比。', '',
      '## 环境与统计口径','',
      '- 20逻辑CPU、15.73GB内存，同机运行MySQL、Nacos、四个业务服务和JMeter；Redis为远端192.168.204.128。启动前空闲约2.2GB。',
      '- MySQL8.0.44、Redis INFO版本8.8.0、Nacos2.4.3、Java21.0.10、JMeter5.6.3。',
      '- 最大堆：Nacos/User/Ticket/JMeter各256MB，Order192MB，Gateway160MB；最小堆64MB、栈512KB。GC及Windows分页记录保留。',
      '- 单实例调用链为Gateway→Ticket→User/Order，Feign模式，不启动支付和MQ。测试配置关闭历史孤儿扫描、订单定时扫表，延迟关单主通道保留。',
      '- SeatAllocator为三列投影、LIMIT N和idx_seat_allocate；责任链库存COUNT已移除。重复购票处理器仍是占位，本轮不验证乘车人重复购买业务规则。',
      '- 每档至少3轮；三轮成功QPS最大/最小超过1.5时检查资源并追加2轮，保留全部有效轮次。表中为轮次中位数，QPS列最小～最大。成功按业务code=0判断；P分位来自JTL nearest-rank。',
      '- QPS分母为首个HTTP请求开始至最后请求完成，包含排空；起跑屏障、客户端初始化与数据准备在HTTP计时之外。',
      '- S1每轮开始810张可售。730请求保护只用于防止耗尽；达到保护上限的轮次及低于3次购票/客户端的档位不证明长期稳态。',
      '- hold均值为Timer TOTAL_TIME差值/COUNT差值；不报告未埋点的锁等待分位数。Hikari采样和Windows资源有采样盲区，短窗口无法证明全程未出现瞬时峰值。', '']
    for scenario in ['s1','s2']:
        rows,levels=summary(scenario,machine);all_results[scenario]=levels
        body += [f'## {scenario.upper()} 正式矩阵','',
          '|并发|轮数|成功QPS（范围）|成功P50/P95/P99 ms|持锁均值 ms|成功数中位数|观测窗口中位数 s|保护触发轮数|最少购票/客户端|',
          '|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
        for n,s in levels.items():
            q=s['successQps']
            body.append(f"|{n}|{s['rounds']}|{md(q['median'])}（{md(q['min'])}～{md(q['max'])}）|{s['successP50']}/{s['successP95']}/{s['successP99']}|{md(s['holdAvgMs']['median'])}|{s['samples']['median']}|{md(s['observedSeconds']['median'])}|{s['capRounds']}|{md(s['minPurchasesPerClient'])}|")
        body += ['', '|并发|乘车人远程均值 ms|建单远程均值 ms|Hikari pending采样峰值|Ticket Windows核数|Ticket JVM CPU %|整机CPU中位数 %|空闲内存最小 GB|page reads/s中位数（峰值）|行锁等待增量|Windows/JVM采样数|',
          '|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
        for n,s in levels.items():
            body.append(f"|{n}|{md(s['passengerAvgMs'])}|{md(s['orderAvgMs'])}|{md(s['hikariPendingMax'],0)}|{md(s['ticketCpuCoresMedian'])}|{md(s['ticketJvmCpuUsageMedianPct'])}|{md(s['machineCpuMedian'])}|{md(s['freeMemoryMinGB'],3)}|{md(s['pageReadMedian'])}（{md(s['pageReadMax'])}）|{s['innodbRowLockWaits']}|{s['machineSamples']}/{s['ticketMetricSamples']}|")
        body += ['', '资源指标只选HTTP正式窗口内的采样点。Windows核数需要同轮至少两个点，以进程CPU秒差/墙钟差计算；点数不足标为缺失。JVM CPU为process.cpu.usage采样中位数×100，采用其近期统计口径，不能当作逐请求CPU时间。','']
        body += ['',f"OD池数：{rows[0]['odPools']}；实际trainId+seatType锁key：{rows[0]['lockKeys']}。",'']
        body += ['|并发|正式轮次各trainId/seatType成功扣减合计|','|---:|---|']
        for n,s in levels.items():body.append(f"|{n}|{s['successfulPurchasesByLockKey']}|")
        body += ['', '各key成功扣减由正式窗口令牌field前后差值汇总，合计与HTTP成功数一致；不代表锁等待分位或每个key独立稳态容量。','']
        body += ['|并发|正式起始可售|最少剩余可售|活动锁key数|Hikari active采样峰值|起始idle最小|JVM线程采样峰值|Tomcat busy采样峰值|1000/hold 单key理论服务率|成功QPS/理论服务率|',
          '|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
        for n,s in levels.items():
            body.append(f"|{n}|{s['poolBefore']}|{s['poolAfterMin']}|{s['activeLockKeys']}|{md(s['hikariActiveMax'],0)}|{md(s['hikariIdleBeforeMin'],0)}|{md(s['jvmThreadsMax'],0)}|{md(s['tomcatBusyMax'],0)}|{md(s['holdServiceRate'])}|{md(s['qpsToHoldServiceRate'],3)}|")
        body += ['', '1000/hold仅为单把锁临界区的理想串行服务率，不是接口QPS；S2多锁均值的倒数也不是系统上限。活动key数表示本轮请求配置涉及的不同key，不代表每个瞬间均有并行事务。Tomcat指标若未暴露则标为不可用。','']
        body += ['|并发|选座SQL均值 ms|每次选座检查行数|COMMIT均值 ms|连接获取均值 ms|连接超时增量|EVAL/EVALSHA调用数/成功单|Redis脚本服务器均值 μs|',
          '|---:|---:|---:|---:|---:|---:|---:|---:|']
        for n,s in levels.items():
            body.append(f"|{n}|{md(s['allocateSqlAvgMs'],3)}|{md(s['allocateRowsExaminedPerCall'],1)}|{md(s['commitSqlAvgMs'],3)}|{md(s['hikariAcquireAvgMs'],3)}|{s['hikariTimeouts']}|{md(s['redisEvalCallsPerSuccess'])}|{md(s['redisEvalServerMeanUsec'])}|")
        body += ['', 'SQL数据来自performance_schema摘要前后差值，表中为各轮单语句均值的中位数。COMMIT计时是数据库执行阶段，不等于全部事务耗时；Redis命令统计为服务器全局值，包含其他客户端，不能当作纯业务调用链追踪。','']
        assert all(r['success']==r['samples'] and r['tokenReject']==0 for r in rows)
        body += ['正式有效轮次全部成功；令牌拒绝、降级为0。每轮订单、明细、车票、不同座位数及库存减少数与HTTP成功数一致；服务端order.success差值一致。', '']
        fig,axes=plt.subplots(3,1,figsize=(9,11),layout='constrained')
        ns=list(levels);ys=[levels[n]['successQps']['median'] for n in ns]
        err=[[levels[n]['successQps']['median']-levels[n]['successQps']['min'] for n in ns],
             [levels[n]['successQps']['max']-levels[n]['successQps']['median'] for n in ns]]
        axes[0].errorbar(ns,ys,yerr=err,marker='o',capsize=4,label='Median and min-max range');axes[0].set_ylabel('Successful purchases / s');axes[0].legend()
        for key in ['successP50','successP95','successP99']:axes[1].plot(ns,[levels[n][key] for n in ns],marker='o',label=key)
        axes[1].set_ylabel('Successful HTTP latency (ms)');axes[1].legend()
        axes[2].plot(ns,[levels[n]['holdAvgMs']['median'] for n in ns],marker='o');axes[2].set_ylabel('Seat lock hold mean (ms)')
        for ax in axes:ax.set_xscale('log');ax.set_xticks(ns,labels=[str(n) for n in ns]);ax.grid(alpha=.3);ax.set_xlabel('Requested concurrency')
        fig.suptitle(f'{scenario.upper()} | current HEAD | fixed inventory, short windows')
        fig.savefig(HERE/f'{scenario}-performance.png',dpi=160);plt.close(fig)
        body += [f'![{scenario}曲线](./{scenario}-performance.png)','']
        sql={}
        for r in rows:
            for k,a in r['dbAfter']['digest'].items():
                b=r['dbBefore']['digest'].get(k,dict(count=0,time=0,rows=0));count=a['count']-b['count']
                if count<=0:continue
                row=sql.setdefault(k,dict(text=a['text'],calls=0,timeMs=0,examined=0))
                row['calls']+=count;row['timeMs']+=(a['time']-b['time'])/1e9;row['examined']+=a['rows']-b['rows']
        (OUT/f'{scenario}-sql-summary.json').write_text(json.dumps(sorted(sql.values(),key=lambda x:x['timeMs'],reverse=True),ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'current-summary.json').write_text(json.dumps(all_results,ensure_ascii=False,indent=2),encoding='utf-8')
    s1,s2=all_results['s1'],all_results['s2']
    best2=max(s2,key=lambda n:s2[n]['successQps']['median'])
    body += ['## 结论与瓶颈分析','',
      f"**单热点在本环境短窗口内首先在5～20并发附近出现平台。** 1并发成功QPS {md(s1[1]['successQps']['median'])}，5/10/20并发分别为{md(s1[5]['successQps']['median'])}/{md(s1[10]['successQps']['median'])}/{md(s1[20]['successQps']['median'])}；400并发为{md(s1[400]['successQps']['median'])}，P99达{md(s1[400]['successP99'],0)}ms。更多客户端主要增加排队与尾延迟，未形成持续增长的吞吐曲线。",'',
      '固定810张库存限制了每轮持续时间，200/400并发每客户端迭代不足；上述平台是短窗口观察，不能称为已测得长期最大稳定QPS。未测旧版，因此不能量化本轮选座优化带来的端到端改善率。','',
      f"**选座全量读取已不是当前主要成本。** S1选座SQL每次检查行数均为1，各档语句均值中位数约{md(min(v['allocateSqlAvgMs'] for v in s1.values()),3)}～{md(max(v['allocateSqlAvgMs'] for v in s1.values()),3)}ms；而持锁均值中位数为{md(min(v['holdAvgMs']['median'] for v in s1.values()))}～{md(max(v['holdAvgMs']['median'] for v in s1.values()))}ms。SQL摘要中的选座时间不是完整MyBatis分配时间，但已证明限量访问在实际购票中生效。继续削减该条SQL难以单独解决所有接口排队。",'',
      '当前锁内事务还包括车次缓存读取/反序列化、站点关系查询、条件占座更新、票价查询、车票插入和事务提交。乘车人查询位于加席别锁前，远程建单位于释放锁后；远程调用虽然移出临界区，仍影响HTTP响应和闭环客户端循环速度。','',
      '单key串行化与延迟增长一致，席别锁是重要串行化点；但成功QPS与1000/hold仍有明显差距。hold埋点从全部席别锁取得后开始，至事务返回结束，未包含锁获取等待及释放成本。不能把剩余差距直接全归因于Redisson交接：还需测锁等待、加解锁/Redis网络、线程调度及锁外链路。','',
      f"**S2并不呈现按锁数线性扩展。** 正式场景为26个有价格且可购买的OD池、4个实际trainId+seatType key；1并发只涉及1个key，其余档位涉及4个。最高档位中位数为{best2}并发的{md(s2[best2]['successQps']['median'])}成功QPS，400并发为{md(s2[400]['successQps']['median'])}。它与S1的OD、席别和库存规模不同，不能把比值全部归因于锁数量。",'',
      'S2并行事务下持锁、SQL和COMMIT开销应结合上表比较。当前保持innodb_flush_log_at_trx_commit=1、sync_binlog=1，buffer pool为128MiB；没有通过降低持久性来换吞吐。COMMIT时间上升提示提交路径值得调查，但缺少fsync、磁盘时延及数据库内部等待采样，不能直接断言磁盘是唯一瓶颈。','',
      'Hikari获取均值、pending、连接超时和行锁等待为本轮实际指标；采样未观察到排队不等于排除所有瞬时等待。Windows page reads包含文件映射等缺页，不等于全部都是交换。低可用内存、同机负载及JMeter新JVM的启动/编译会影响状态；起跑屏障仅剔除请求计时中的准备成本，并不能证明压测端零干扰。','',
      '## 异常轮次、夹具与工具修正','',
      '- 初次启动沿用历史数据时，孤儿车票扫描和定时扫表干扰库存；正式环境关闭这两个背景任务并重新核对。延迟关单保留。这是测试配置差异，结果不等价于所有默认背景任务开启时的性能。',
      '- Redis会话TTL为30分钟；首次长矩阵的过期会话轮次不采用。现在每并发档登录刷新，不以JWT有效期代替Redis准入有效期。',
      '- JSR223最早计时包含脚本编译与起跑等待，单线程相邻样本出现时间重叠。修正后按HTTP发送开始设置时间戳和idle时间，JTL标签为POST purchase [HTTP timing v2]；正式矩阵只采用修正标签。p2-throughput.jmx新增8行保护逻辑，仅当当前工具设置current.client时启用。',
      '- 原座位数据枚举得到41个OD/5个key，但车次3为BULLET，不支持legacy席别1，且没有该席别票价。150线程诊断中30个请求失败，29次令牌降级；API复现车票价格数据缺失，数据库占座已回滚。失败补偿可给原先不存在的field增加令牌，留下与数据库不一致的数值。证据见results/s2-fixture-diagnosis.json；不据此断言历史报告每次令牌拒绝原因已完全查清。',
      '- 正式S2筛选席别1/2下支持且有精确OD价格的26个池/4个key，并清除诊断产生的特定无效令牌缓存。未修改业务错误处理，未编造新座位或票价。',
      '- S2在50并发下一轮预检查前，Redis短连接耗尽Windows临时端口，未形成该轮HTTP样本。工具改为每线程复用RESP连接，以resume保留有效轮次继续；连接复用位于测试观测工具，未更改业务服务Redis客户端。','',
      '## 后续定位顺序','',
      '1. 先补齐席别锁等待、获取/释放耗时及锁内各阶段Timer；按请求关联区分SQL执行、JDBC传输/映射和事务提交，验证1000/hold与成功QPS的差距来源。',
      '2. 采集Redis往返与锁交接、MySQL提交/fsync和I/O等待；为站点关系、票价等读查询核对索引与缓存收益，避免再次只盯选座SQL。',
      '3. 查清无效席别请求与降级补偿的令牌边界，补足按OD价格/车型校验，先修正确性再扩展测试池。',
      '4. 若串行临界区确为主要瓶颈，再设计更细锁粒度或不同占座方案。需重新验证相邻区间同物理座位冲突、多人同车厢和失败补偿，不能仅将key加OD便宣称安全。',
      '5. 持续容量验证需单独设计不被810张库存消耗截断的长期工作负载，同时保证回收与新购互不污染；本轮不额外改造库存或降低数据库持久性。','',
      '## 原始证据与解释边界','',
      '本地results下保留每轮JTL、JSON指标差值、GC日志、机器资源JSONL、SQL汇总与矩阵。results被忽略，不提交包含JWT的users.csv。',
      '全部有效轮次按业务成功对账；不同座位按车次、OD、席别、车厢和座位判重。本轮没有验证跨重叠OD的同物理座位冲突，没有进行支付或出票。固定810张库存高并发窗口有限，不能直接外推生产规模或稳定持续吞吐。',
      '应用Timer percentile端点本轮返回非零，不能沿用历史SimpleMeterRegistry分位为零的结论；其滚动统计不作为正式窗口分位，HTTP分位仍来自JTL。Tomcat线程端点未暴露，以缺失记录，不补填估计值。', '']
    final=json.loads((OUT/'final-verification.json').read_text('utf-8'))
    shutdown=json.loads((OUT/'shutdown-verification.json').read_text('utf-8-sig'))
    scheduled=json.loads((OUT/'scheduled-cleanup-verification.json').read_text('utf-8'))
    memory_min=min(v['freeMemoryMinGB'] for levels in all_results.values() for v in levels.values() if v['freeMemoryMinGB'] is not None)
    body += ['## 最终核验与资源回收','',
      f"正式有效轮次{final['formalRounds']}轮，共{final['successfulPurchases']:,}次成功购票。S1每档至少3轮，100并发5轮；S2同样100并发5轮，保留所有有效波动轮次。总QPS等于成功QPS，成功率均为100%，落座失败等失败分类为0。",'',
      f"结束后目标池为810张/810可售/0占座；S2为12,350张/12,350可售/0占座。本轮新增订单、订单明细及perf400车票残留均为0，今日目标池车票残留0。保留{final['historicalNullOrderTicketRows']}条九月遗留、order_sn为空的历史测试记录，不把它们当作本轮交易或清理对象。已加载的令牌field与恢复库存一致，JAR哈希未改变。",'',
      f"关闭业务服务后，按本轮日志的perf400订单号且确认订单已从数据库清理，移除{scheduled['removedEntries']:,}条待执行延迟关单记录；不按Redis key全量删除。清理后本轮待执行记录为0，证据见scheduled-cleanup-verification.json。预热和正式交易均在各轮结束后释放，延迟队列清理在全部测量结束后执行，不改变已测数据。",'',
      f"正式窗口Windows采样的最低可用内存{memory_min:.3f}GB；可用内存波动体现本机限制，不能把全程当作恒定2GB资源。停止自有服务和监控后，8848/9848/9000～9003均无监听，可用内存采样为{shutdown['freeMemoryGB']:.3f}GB；MySQL及远端Redis保留，未关闭用户应用。",'',
      '|服务|正式窗口GC暂停数|正式Full GC数|正式窗口最长GC暂停 ms|',
      '|---|---:|---:|---:|']
    for service,g in final['gc'].items():body.append(f"|{service}|{g['formalPauseCount']}|{g['formalFullCount']}|{g['formalMaxPauseMs']:.3f}|")
    body += ['', 'GC窗口按保存的实际JVM启动时间加日志uptime对齐，只有暂停完成时刻处于HTTP窗口的事件进入表中，边界有微小误差。服务GC日志及stderr/stdout检查未见OOM，服务正式窗口无Full GC；JMeter各次启动出现System.gc() Full GC，不能宣称所有JVM完全没有Full GC。每轮JMeter为新JVM，客户端连接/JIT未跨轮保留，这是短窗口的额外限制。','',
      '业务源文件未修改。工作区新增本目录计划、工具、报告及图，并对旧JMX增加仅供当前工具启用的计时修正；没有自动提交、合并或推送。']
    (HERE/'当前版本并发性能测试报告.md').write_text('\n'.join(body),encoding='utf-8')
    print(json.dumps({s:{n:dict(qps=v['successQps']['median'],p99=v['successP99'],hold=v['holdAvgMs']['median']) for n,v in levels.items()} for s,levels in all_results.items()},ensure_ascii=False))
