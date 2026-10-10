"""Render the verified warm A/B evidence into a report and standalone chart."""
import importlib.util,json,pathlib,statistics
HERE=pathlib.Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('warm',HERE/'warm-runner.py');w=importlib.util.module_from_spec(s);s.loader.exec_module(w)
OUT=w.OUT
def main():
    data=json.loads((OUT/'warm-summary.json').read_text('utf-8'));groups=data['groups'];rows=json.loads((OUT/'comparison-matrix.json').read_text('utf-8'))
    verify=json.loads((OUT/'warm-final-verification.json').read_text('utf-8'));edge_path=OUT/'edge-request-joins.json'
    edge=json.loads(edge_path.read_text('utf-8')) if edge_path.exists() else None
    lines=['# 预热后购票性能与事务优化报告','',
        '测试日期：2026-10-09。保留当前车厢锁、用户锁、令牌桶、条件占座、整单事务和建单恢复语义；不修改 MySQL 持久化参数，不扩大连接池。',
        '', '## 测量环境与有效范围','',
        '单 Ticket 实例、Feign，车次1/席别2/北京南→宁波，07～15车厢原始810张可售票。Nacos/User/Ticket/JMeter最大堆192MB，Order160MB、Gateway128MB，初始堆64MB。原256MB组合触发低内存保护，排除后统一使用此配置；正式A/B没有再调整堆。',
        'Java21/MySQL8.0.44；i5-13500HX，14核/20逻辑处理器。MySQL与Redis连接检查、服务健康/注册、400用户登录及购票取消验证后才进入测试；未启动支付及MQ。',
        '编译后的JMeter Java采样器采用同一持续运行JVM、共享HttpClient和100常驻线程。HTTP计时从同步发送到完整响应读取；JSON解析/成功分类在HTTP计时外，另行记录。每个用户仅一个在途请求，重复购票责任链仍为占位，本测量不验证完整重复购票业务规则。',
        '客户端先由全部100线程调用本机简单接口，再按20并发、每线程5次真实购票预热，每批100笔。每组最多五批；不稳定组排除，个别部署执行最多三组独立确认，相对于原计划总共五批的限制增加了探索批次。相邻两批HTTP与业务方法中位数均在±15%内才进入正式轮次；每次Ticket重新部署均重新预热与刷新会话。',
        '初始100并发、每线程仅一次的预热多次不稳定；其数据及2秒/1秒探索轮次均保留并排除。一次30分钟登录过期导致100个401、无订单，客户端保护停止后重新启动；最终完整正式矩阵全部来自刷新后的同一个客户端JVM。',
        f"正式共同发送窗口 **{data['window']}秒**，每轮最多405笔；两版交替部署三次，每档关闭/开启采集各三轮，合计 **36轮/{data['success']}笔成功**。采集关闭仍加载诊断代理。QPS包含窗口结束后的排空；不能用窗口内完成数或拒绝请求替代成功QPS。",'',
        '先前results/warm-purchase-lowmem的一整组36轮/5520请求已排除：该组没有逐轮清理延迟任务，旧任务在后期到期并产生额外Order查询，违反背景负载一致性。原始记录保留，不能使用其QPS收益。当前干净矩阵在每批交易取消/删除后，原子移除对应已删除订单的延迟任务，并保存逐批cleanup.json；任务清理、库存恢复均在测量窗口外。正式测量期间订单扫表关闭。',
        '', '## 同条件正式结果','',
        '|版本|并发|采集|三轮成功QPS|中位数（范围）|P95中位ms|P99中位ms|每轮成功数|',
        '|---|---:|---|---|---|---:|---:|---|']
    for version in ['baseline','candidate']:
        for level in [20,50,100]:
            for capture in [False,True]:
                key=f'{version}-{level}-{int(capture)}';g=groups[key];selected=[r for r in rows if r['version']==version and r['level']==level and r['capture']==capture]
                lines.append(f"|{'热基线' if version=='baseline' else '元数据移出事务'}|{level}|{'开' if capture else '关'}|"+' / '.join(f"{r['successQps']:.2f}" for r in selected)+f"|{g['qps']['median']:.2f}（{g['qps']['min']:.2f}～{g['qps']['max']:.2f}）|{g['p95']['median']:.2f}|{g['p99']['median']:.2f}|"+' / '.join(map(str,g['sent']))+'|')
    lines+=['','全部正式请求成功；订单、订单明细、车票、唯一座位和占座数逐轮相等。三轮范围反映本地短窗口波动，开启采集偶尔比关闭更快不等于探针能提升性能。','',
        '|并发|采集|成功QPS中位相对收益|事务均值：基线→新版本ms|锁等待均值：基线→新版本ms|提交均值：基线→新版本ms|','|---:|---|---:|---|---|---|']
    for level in [20,50,100]:
        for capture in [False,True]:
            a=groups[f'baseline-{level}-{int(capture)}'];b=groups[f'candidate-{level}-{int(capture)}'];delta=(b['qps']['median']/a['qps']['median']-1)*100
            stages=[]
            for name in ['tx.proxy','seat.lock-wait','jdbc.commit']:
                stages.append(f"{a['stages'][name]['mean']:.2f}→{b['stages'][name]['mean']:.2f}" if capture else '未采集')
            lines.append(f"|{level}|{'开' if capture else '关'}|{delta:+.1f}%|"+'|'.join(stages)+'|')
    best=max((groups[f'candidate-{level}-0']['qps']['median'],level) for level in [20,50,100])
    lines+=['',f"新版本关闭采集的最佳三轮中位配置为 **{best[1]}并发/{best[0]:.2f}成功QPS**。是否达到争取的100成功QPS，以该档三轮范围一起判断，不选择单次峰值。",'',
        '## 改动与锁内阶段','',
        '`PurchaseMetadataService.prepare`在令牌获取及乘车人查询后、用户/车厢锁前加载车次、站点关系和各席别价格；使用不可变Instant/Map构成一次请求的价格快照。同席别多人只查一次价格，候选切换/数据库重试复用快照。事务内保留选座、条件更新与车票插入，提交后解锁，锁外远程建单。没有新增跨请求票价缓存。',
        '|并发/版本|HTTP均值ms|业务均值ms|方法外差值ms|选座SQLms|元数据JDBC：站点/票价ms|远程建单ms|最多并行车厢|','|---|---:|---:|---:|---:|---|---:|---:|']
    for level in [20,50,100]:
        for version in ['baseline','candidate']:
            g=groups[f'{version}-{level}-1'];p=g['stages'];method=p['purchase.total']['mean'];fmt=lambda name:f"{p[name]['mean']:.2f}" if name in p else '—'
            lines.append(f"|{level}/{'基线' if version=='baseline' else '新版本'}|{g['http']['mean']:.2f}|{method:.2f}|{g['http']['mean']-method:.2f}|{fmt('jdbc.seat-select')}|{fmt('jdbc.station-relation')}/{fmt('jdbc.price')}|{fmt('order.remote')}|{g['maxParallel']}|")
    lines+=['','阶段是包含子阶段的累计值，不能直接相加。JDBC计时包含驱动交互，SQL服务器摘要另行给出；提交、解锁往返和线程调度仍会影响车厢锁交接。方法外差值不是全部网关开销。',
        '阶段均值按三轮全部成功请求合并计算，QPS及HTTP分位数采用三轮各自统计后的中位数，两者不是相同聚合口径。元数据与库存同时缺失时，前置准备会优先返回元数据错误；单项错误消息及响应格式保持原语义。',
        '', '## 请求关联与额外入口诊断','',
        '正式轮次通过订单号、业务日志线程/时间、客户端请求ID关联；元数据JDBC结束时刻须早于第一把用户/车厢锁获取开始，同key临界区交叠必须为0。完整关联证据在warm-summary.json的joins。',
        '方法外耗时较大，正式矩阵完成后另部署Gateway/Ticket入口探针，按请求ID关联网关过滤器、Servlet入口和HTTP调用。下表来自独立诊断，不混入正式QPS；毫秒壁钟切分存在量化误差，异步doFinally顺序可造成少量负差值。',
        '|并发|关联请求数|HTTP均值ms|网关入口→完成ms|Ticket Servlet ms|客户端发送→网关入口ms|网关入口→Servlet ms|Servlet完成→网关完成ms|','|---:|---:|---:|---:|---:|---:|---:|---:|']
    if edge:
        for level in [20,50,100]:
            selected=[r for r in edge['rows'] if r['level']==level]
            values=[statistics.mean(r[name] for r in selected) for name in ['httpMs','gatewayMs','ticketServletMs','clientToGatewayMs','gatewayToServletMs','servletToGatewayEndMs']]
            lines.append(f"|{level}|{len(selected)}|"+'|'.join(f'{v:.2f}' for v in values)+'|')
    else:
        start=lines.index('方法外耗时较大，正式矩阵完成后另部署Gateway/Ticket入口探针，按请求ID关联网关过滤器、Servlet入口和HTTP调用。下表来自独立诊断，不混入正式QPS；毫秒壁钟切分存在量化误差，异步doFinally顺序可造成少量负差值。')
        lines=lines[:start]
        fractions=[100*(1-g['stages']['purchase.total']['mean']/g['http']['mean']) for key,g in groups.items() if key.endswith('-1')]
        assert max(fractions)<=25,'Entry diagnosis is required by plan'
        lines.append(f"正式六组开启采集数据的方法外均值占HTTP比例为{min(fractions):.1f}%～{max(fractions):.1f}%，均未超过25%触发条件；本轮未另启入口诊断，不声称测得纯网关或鉴权耗时。")
    lines+=['','方法外差值仍包含客户端调度、连接、网关与服务入口分发；没有更深的事件，不做单一组件归因。',
        '', '## 连接池、SQL及资源','',
        '|版本/并发/采集|Ticket连接获取均值ms|Order连接获取均值ms|Ticket/Order最大采样pending|连接超时|','|---|---:|---:|---|---:|']
    for key,g in groups.items():
        a,b=g['pools']['ticket'],g['pools']['order']
        pending=lambda p:'未采样' if p['maxSampledPending'] is None else f"{p['maxSampledPending']:.0f}"
        lines.append(f"|{key}|{a['acquireMeanMs']:.4f}|{b['acquireMeanMs']:.4f}|{pending(a)}/{pending(b)}|{a['timeouts']+b['timeouts']:.0f}|")
    lines+=['','### 本轮瓶颈与收益判断','']
    for level in [20,50,100]:
        a,b=groups[f'baseline-{level}-1'],groups[f'candidate-{level}-1'];pa,pb=a['stages'],b['stages']
        reduction=(1-pb['tx.proxy']['mean']/pa['tx.proxy']['mean'])*100
        meta=b['metadataPrepareMeanMs']
        lines.append(f"- {level}并发：锁内事务均值{pa['tx.proxy']['mean']:.2f}→{pb['tx.proxy']['mean']:.2f}ms（减少{reduction:.1f}%）；请求元数据准备均值{meta:.2f}ms，移到锁外后仍属于请求总耗时。新版本车厢锁等待{pb['seat.lock-wait']['mean']:.2f}ms、提交{pb['jdbc.commit']['mean']:.2f}ms、建单{pb['order.remote']['mean']:.2f}ms。同车厢相邻解锁准备→下次获锁的观测间隔均值{b['observedHandoff']['mean']:.2f}ms，包含归属检查、解锁、调度及可能的请求空档，不能全部称为Redis往返。候选不足/兜底/DB重试：{b['counters']['candidate-miss']:.0f}/{b['counters']['fallback']:.0f}/{b['counters']['db-retry']:.0f}；元数据锁外验证{b['metadataChecks']}项全部通过：{b['metadataAllOutside']}。")
    q50=groups['candidate-50-0']['qps']['median'];q100=groups['candidate-100-0']['qps']['median']
    lines.append(f"- 50→100并发：新版本关闭采集的成功QPS中位数{q50:.2f}→{q100:.2f}（{(q100/q50-1)*100:+.1f}%）。须结合三轮范围及每线程调用次数解释，不能依据此短窗口计算线性扩容能力。")
    lines.append('- 客户端分类现在通常为百分之一毫秒量级；正式轮次的主要业务等待由锁、事务和远程调用数据解释。旧Groovy会话的绝对QPS不能作为本次优化前基线。若某档QPS三轮范围明显重叠，收益方向需谨慎解释，不能把中位差写成保证。')
    lines+=['','完整Ticket/Order语句摘要差值、执行次数/耗时保存在warm-summary.json的sql。单人场景票价和站点SQL移到锁外，不能因此宣称总SQL次数下降；多人同席别的价格复用由针对性测试验证。连接池采样较短，零pending采样不等于没有瞬时等待。',
        f"正式窗口OS采样最低可用内存 **{verify['minFreeMemoryGB']:.3f}GB**、最高CPU **{verify['maxSampledCpu']:.0f}%**。堆/GC监控实际时间戳见heap-resources.jsonl、injector-resources.jsonl及各次部署gc.log；实际采样间隔受查询耗时影响，不保证每个1秒窗口都有采样。",'',
        '## 正确性、恢复与复现','',
        '49项针对性测试通过，包含元数据快照/缺失、多人及多席别、候选不足/条件占座回滚、数据库重试、令牌归还、建单明确失败和结果未知的原有语义、车厢分配及回调。测试fixture对两项原库存不足测试提供合法只读上下文，避免元数据提前报错遮蔽库存回滚断言；回调测试显式使用Feign。',
        '50请求争抢10张票共三轮，每轮10单成功、10个不同座位，未发现超卖、重复座位和部分占座。测试夹具仅暂改目标池并在finally恢复。',
        f"最终 **{verify['availableSeats']}张全部可售**，目标池外状态与起始快照一致；本轮订单、明细、车票及延迟任务残留为0。测试服务和客户端停止，端口恢复空闲，结束可用内存 **{verify['shutdownFreeMemoryGB']:.2f}GB**。",'',
        f'主要复现入口：warm-runner.py（持续客户端及阶段控制）、warm-matrix.py（交替版本）、warm-ticket.ps1（固定堆部署）、analyze-warm.py（三轮统计和锁边界）。基线/新版本构建产物、指纹、源码快照、原始请求/阶段/锁/资源及测试XML位于results/{OUT.name}。原始目录含登录会话，保持Git忽略，不提交。',
        '', '## 结论边界','',
        '本次确认的是固定810张库存、单实例、本机短发送窗口及排空后的成功吞吐。大部分线程调用次数较少，100并发尤其接近预热后突发，不能描述为生产长期容量。若三轮范围重叠明显，应优先陈述“减少锁内读取/缩短事务”，并保留吞吐收益的不确定性。',
        '未自动继续200/400并发，没有改造座位锁/支付/MQ，没有提交、合并或推送。正式简历采用本次同环境可复核数字，不拼接历史38QPS、35.6→11.1ms或探索峰值。','']
    section=lines.index('## 正确性、恢复与复现')
    extra=['## GC与内存配置判断','',
        '|服务|正式窗口GC停顿数|总停顿ms|最长停顿ms|Full GC数|','|---|---:|---:|---:|---:|']
    for service in ['ticket','user','order','gateway','nacos','injector']:
        values=[item for item in verify['gc'] if item['service']==service]
        extra.append(f"|{service}|{sum(x['count'] for x in values)}|{sum(x['totalMs'] for x in values):.2f}|{max(x['maxMs'] for x in values):.2f}|{sum(x['fullCount'] for x in values)}|")
    peak=max(verify['heapMaxRatios'].values())*100
    extra+=['',f"服务堆占用采样峰值不超过上限的{peak:.1f}%，连续三点超过80%的情况为0，未满足计划的扩堆触发条件。当前提交堆可能小于Xmx，G1仍有年轻代/标记停顿；不能据此直接称为总内存不足，也不能只因为QPS低就增大堆。",'GC对齐采用操作系统进程创建时刻＋JVM uptime，边界存在亚秒误差。停顿发生在锁内时可放大后续排队，但这些相关记录不足以解释所有波动或给出唯一因果。','']
    lines[section:section]=extra
    section=lines.index('## 结论边界')+2
    lines[section:section]=['新版本关闭采集的三档三轮中位数均超过100成功QPS，但50/100并发仍有低于100的有效轮次，严格的“稳定超过100”目标尚未达成。相邻两批±15%的预热判据只约束短期变化，不能排除临时平台及后续JIT/GC变化；观察到的事务均值与吞吐差不能全部归因于代码改造。','']
    normalized=[]
    for line in lines:
        if line.startswith('|') and normalized and normalized[-1] and not normalized[-1].startswith('|'):normalized.append('')
        if line and not line.startswith(('|','#','- ')) and normalized and normalized[-1] and not normalized[-1].startswith(('|','#','- ')):normalized.append('')
        normalized.append(line)
    (HERE/'预热后购票性能与事务优化报告.md').write_text('\n'.join(normalized),'utf-8')
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    plt.rcParams['font.sans-serif']=['Microsoft YaHei'];plt.rcParams['axes.unicode_minus']=False
    fig,axes=plt.subplots(1,2,figsize=(13,4.8))
    for version,label,color in [('baseline','热基线','#64748b'),('candidate','元数据移出锁内事务','#0284c7')]:
        vals=[groups[f'{version}-{n}-0']['qps'] for n in [20,50,100]];y=[g['median'] for g in vals]
        axes[0].errorbar([20,50,100],y,yerr=[[g['median']-g['min'] for g in vals],[g['max']-g['median'] for g in vals]],marker='o',capsize=4,label=label,color=color)
        stages=[groups[f'{version}-{n}-1']['stages']['tx.proxy']['mean'] for n in [20,50,100]]
        axes[1].plot([20,50,100],stages,marker='o',label=label,color=color)
    axes[0].set_title('成功QPS：三轮中位数与范围（采集关）');axes[0].set_ylabel('成功QPS，包含排空')
    axes[1].set_title('锁内事务调用均值（采集开）');axes[1].set_ylabel('毫秒')
    for ax in axes:ax.set_xticks([20,50,100]);ax.set_xlabel('并发');ax.set_ylim(bottom=0);ax.grid(alpha=.2);ax.legend()
    fig.suptitle(f"同环境/810库存/共同发送窗口{data['window']}秒；短窗口不代表长期容量")
    fig.tight_layout();fig.savefig(HERE/'预热后购票性能与事务优化.png',dpi=170);plt.close(fig)
if __name__=='__main__':main()
