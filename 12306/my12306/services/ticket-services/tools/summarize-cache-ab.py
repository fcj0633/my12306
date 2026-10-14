"""Report individual rounds and medians; never combine HTTP and in-process service throughput."""
import json, statistics, collections
from pathlib import Path

ROOT=Path(__file__).resolve().parents[5]
DOCS=ROOT/'docs/12-cache-optimization'
groups=collections.defaultdict(dict)
for path in sorted((DOCS/'evidence').glob('[AB]-*.json')):
    row=json.loads(path.read_text(encoding='utf-8'))
    if not row.get('runId'):continue
    if row.get('configuredSeconds')!=60 or row.get('concurrency') not in (50,100):continue
    k=(row['scenario'],row['concurrency'],row.get('httpWarmSeconds',0),row.get('httpTimingScope','legacy-call'),row['model'])
    old=groups[k].get(row['round'])
    if old is None or path.stat().st_mtime>old[0].stat().st_mtime:groups[k][row['round']]=(path,row)

def metric(row,prefix,stat='COUNT'):
    return sum(v for k,v in row['metrics'].items() if k.startswith(prefix) and k.endswith('/'+stat))

def observation(row):
    elapsed=row['elapsedSeconds']
    summary={'model':row['model'],'round':row['round'],'elapsedSeconds':elapsed,'operations':{},'errors':row['operations'].get('errors',{}),
        'sql':metric(row,'my12306.cache.sql'),'sqlQps':metric(row,'my12306.cache.sql')/elapsed,
        'remainingLoads':metric(row,'my12306.inventory.remaining.load'),'deleteCalls':metric(row,'my12306.cache.delete.calls'),
        'requestedKeys':metric(row,'my12306.cache.delete.requested'),'deletedKeys':metric(row,'my12306.cache.delete.keys'),
        'versionRejects':metric(row,'my12306.cache.version.reject'),'versionReloads':metric(row,'my12306.cache.version.reload'),'versionAbandons':metric(row,'my12306.cache.version.abandon'),
        'pendingEvents':row['pendingEvents'],'activeSegments':row['activeSegments'],
        'sqlByCategory':{k:v for k,v in row['metrics'].items() if k.startswith('my12306.cache.sql')},
        'eventMetrics':{k:v for k,v in row['metrics'].items() if k.startswith('my12306.inventory.cache-event')},
        'cacheMutationMetrics':{k:v for k,v in row['metrics'].items() if k.startswith('my12306.cache.delete') or k.startswith('my12306.cache.version')}}
    for op,values in row['operations'].items():
        if op!='errors':summary['operations'][op]={k:v for k,v in values.items() if k!='latenciesMicros'}
    summary['cacheLoads']={k:v for k,v in row['metrics'].items() if k.startswith('my12306.cache.load') or k.startswith('my12306.cache.lookup') or k.startswith('my12306.cache.lock.wait')}
    if 'propagation' in row:summary['propagation']={k:{a:b for a,b in v.items() if a!='latenciesMicros'} for k,v in row['propagation'].items() if k!='errors'};summary['commitRecords']=row['commitRecords'];summary['matchedInvalidations']=row.get('matchedInvalidations')
    return summary

result=[];lines=['# 缓存 A/B 逐轮数据','', '仅纳入 60 秒、50/100 并发的正式组；同场景同轮次保留最新文件，先前诊断和被中止组仍保留在 evidence。QPS 为各类成功请求数除以完整测量窗口，数据库 SQL 使用实际 MyBatis prepare 计数；服务内调用与 HTTP 分开。','']
for scenario,concurrency,warm,timing in sorted({k[:4] for k in groups}):
    lines+=['## '+scenario+' / '+str(concurrency)+' 并发 / HTTP 并发预热 '+str(warm)+' 秒 / '+timing,'','| 模型/轮次 | 查询 QPS | 查询 P99 ms | 占座 QPS | 取消 QPS | SQL QPS | 余票加载 | 版本拒绝 |','|---|---:|---:|---:|---:|---:|---:|---:|']
    item={'scenario':scenario,'concurrency':concurrency,'httpWarmSeconds':warm,'httpTimingScope':timing,'models':{}}
    for model in ['A','B']:
        rounds=[]
        for round,(path,row) in sorted(groups.get((scenario,concurrency,warm,timing,model),{}).items()):
            summary=observation(row);summary['file']=path.name;rounds.append(summary);ops=summary['operations']
            q=ops.get('query',{});p=ops.get('purchase',{});c=ops.get('cancel',{})
            lines.append(f"| {model}/{round} | {q.get('qps',0):.2f} | {q.get('p99Ms',0):.2f} | {p.get('qps',0):.2f} | {c.get('qps',0):.2f} | {summary['sqlQps']:.2f} | {summary['remainingLoads']:.0f} | {summary['versionRejects']:.0f} |")
        if rounds:item['models'][model]=rounds
    lines+=['','| 模型/轮次/操作 | 成功数 | 平均 ms | P50 ms | P95 ms | P99 ms |','|---|---:|---:|---:|---:|---:|']
    for model,rounds in item['models'].items():
        for row in rounds:
            for operation,values in row['operations'].items():
                lines.append(f"| {model}/{row['round']}/{operation} | {values['success']} | {values['meanMs']:.3f} | {values['p50Ms']:.3f} | {values['p95Ms']:.3f} | {values['p99Ms']:.3f} |")
            if row['errors'] or row['pendingEvents'] or row['activeSegments']:
                lines.append(f"异常/待处理事件/活动区段：{row['errors']} / {row['pendingEvents']} / {row['activeSegments']}")
    lines.append('');medians={}
    for model,rounds in item['models'].items():
        qs=[r['operations']['query']['qps'] for r in rounds];tails=[r['operations']['query']['p99Ms'] for r in rounds]
        medians[model]={'qps':statistics.median(qs),'p99Ms':statistics.median(tails),'qpsRange':[min(qs),max(qs)],'p99Range':[min(tails),max(tails)]}
        lines.append(f"{model}：查询 QPS 中位数 {statistics.median(qs):.2f}（{min(qs):.2f}～{max(qs):.2f}），P99 中位数 {statistics.median(tails):.2f} ms（{min(tails):.2f}～{max(tails):.2f}）。\n")
    item['medians']=medians
    if set(medians)=={'A','B'}:
        change={k:(medians['B'][k]/medians['A'][k]-1)*100 for k in ['qps','p99Ms']};item['relativePercent']=change
        item['hotNeedsFiveRounds']=scenario=='hot-http' and (change['qps'] < -5 or change['p99Ms']>10)
        lines.append(f"B 相对 A：查询 QPS {change['qps']:+.2f}%，P99 {change['p99Ms']:+.2f}%。\n")
    result.append(item)
(DOCS/'RESULTS.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
(DOCS/'RESULTS.md').write_text('\n'.join(lines),encoding='utf-8')
print('Updated cache report; verify scenario coverage and regression thresholds before accepting.')
