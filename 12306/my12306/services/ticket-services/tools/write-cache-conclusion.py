"""Produce the evidence narrative after all measurements have finished."""
import json, statistics
from pathlib import Path
ROOT=Path(__file__).resolve().parents[5]
DOCS=ROOT/'docs/12-cache-optimization'
EVIDENCE=DOCS/'evidence'

def main():
    groups=json.loads((DOCS/'RESULTS.json').read_text(encoding='utf-8'))
    formal=[g for g in groups if (g['scenario']!='hot-http' or g['httpTimingScope']=='send-to-body')]
    lines=['# 缓存专项最终报告：第 1～3 项','','本轮基于开始时的区段库存工作区建立 A 组，对同一诊断版本实施 Cache Aside 优化作为 B 组。使用真实 MySQL、Redis 和独立数据库/Key 前缀。没有引入 Caffeine、Canal、Binlog 或新 MQ，没有修改库存模型或外部接口。','',
    '当前版本原本已有单 OD 各席别一次库存聚合、30 秒余票 TTL、OD 加载锁与事务失效事件重试。本轮保留这些能力，主要新增空结果缓存、按城市对独立加载锁、快照 TTL 与长期 TTL 抖动、统一车次加载入口，以及防止失效后旧快照回填的随机版本校验。','',
    '## 正式性能结果','','主要场景 50 并发、60 秒、A/B 交替三轮；热点 HTTP 以严格发送到完整响应读取的口径补到五轮，100 并发单独列出。下表为逐轮中位数；完整范围、平均和 P50/P95/P99 见 RESULTS.md，全部指标与原始延迟见 evidence。服务内调用不等于 HTTP 或全链路购票容量。','',
    '| 场景 / 并发 / 轮数每组 | A 查询 QPS | B 查询 QPS | 相对变化 | A / B 查询 P99 ms |','|---|---:|---:|---:|---:|']
    for g in formal:
        a,b=g['medians']['A'],g['medians']['B'];n=len(g['models']['A'])
        lines.append(f"| {g['scenario']} / {g['concurrency']} / {n} | {a['qps']:.2f} | {b['qps']:.2f} | {g['relativePercent']['qps']:+.2f}% | {a['p99Ms']:.2f} / {b['p99Ms']:.2f} |")
    lines+=['','热点组没有证实性能提升。按计划的中位数门槛检查 QPS 下降 5%、P99 上升 10%；全部轮次保留，包括较慢的首轮。历史和旧计时口径的组不用于替换严格口径的验收结果。绝对吞吐随测量阶段有明显波动，不能把单机数值当作生产容量承诺。','']
    for g in formal:
        if g['scenario']=='hot-http':
            passed=not g['hotNeedsFiveRounds']
            lines.append(f"- {g['concurrency']} 并发热点：QPS {g['relativePercent']['qps']:+.2f}%，P99 {g['relativePercent']['p99Ms']:+.2f}%；{'满足本机容差门槛' if passed else '未满足门槛，不能验收'}，每组 {len(g['models']['A'])} 轮。")
    lines+=['','重复查询相同不存在数据时，短 TTL 空值将回源从每请求降为每 TTL 窗口加载；这些场景的改善来自空值复用，不能推广到合法热点或正常购票。高基数 unique-train 每次使用不同 ID，没有复用收益，B 增加加载锁和空值写入，吞吐明显下降。空值缓存不是高基数流量的限流替代方案。','',
    '## 读写混合的实际代价','','混合负载为 80% 查询、10% 单票占座、10% 真实取消。占座经过真实车厢锁和事务代理，取消使用真实回调；乘客与车次元数据作为合法夹具直接传入事务，不含远程 User/Order 建单、Gateway 或完整购票校验。','',
    '| 场景 / 模型 | 占座 QPS 中位数 | 取消 QPS 中位数 | 余票聚合次数中位数 / 60s | 版本拒绝 / 重查 / 放弃回填中位数 |','|---|---:|---:|---:|---:|']
    for g in formal:
        if g['scenario'] not in ('mixed','overlap-mixed'):continue
        for model,rows in g['models'].items():
            med=lambda key:statistics.median(r[key] for r in rows)
            op=lambda key:statistics.median(r['operations'][key]['qps'] for r in rows)
            lines.append(f"| {g['scenario']} / {model} | {op('purchase'):.2f} | {op('cancel'):.2f} | {med('remainingLoads'):.0f} | {med('versionRejects'):.0f} / {med('versionReloads'):.0f} / {med('versionAbandons'):.0f} |")
    lines+=['','B 的普通加载通常少一次车次元数据 SQL，但失效竞争会重复执行成本更高的库存聚合。两种混合场景查询吞吐约下降一成，不能宣称混合性能提升，也不能仅凭总 SQL 减少宣称数据库成本下降。版本机制属于一致性修复；没有独立消融实验，不能把全部下降百分比归因于某一个改动。','',
    '余票加载指标 A 包含发布、B 不包含版本获取与条件发布，不能直接比较 Timer 并宣称单次完整重建变便宜。每轮实际 SQL、加载次数、删除调用及涉及/实际删除 Key 数、重查次数均在 RESULTS.json 和原始文件中。库存聚合仍只针对请求 OD，一次按席别分组；没有整车 OD 在线预热的新收益。','',
    '## 正确性、冷加载与故障证据','']
    selected={}
    for path in sorted(EVIDENCE.glob('[AB]-*.json')):
        if any('-'+x+'-' in path.name for x in ('checks','burst-city','burst-remaining','burst-cold','parallel-proof','expiry','pulses','refill-race','unique-train','mixed','overlap-mixed')):
            row=json.loads(path.read_text(encoding='utf-8'))
            if row.get('runId') and row.get('configuredSeconds',0)!=60:selected[(row['model'],row['scenario'])]=(path,row)
    proofs=[]
    for (model,scenario),(path,row) in sorted(selected.items()):
        small={k:v for k,v in row.items() if k not in ('metrics','operations','propagation')}
        small['file']=path.name
        if 'operations' in row:small['operations']={k:{a:b for a,b in v.items() if a!='latenciesMicros'} if k!='errors' else v for k,v in row['operations'].items()}
        proofs.append(small)
        if scenario=='checks':
            lines.append(f"B 正确性检查通过 {len(row['passed'])} 项："+'、'.join(row['passed'])+'。')
            lines.append(f"确定性回填竞争：B 缓存 {row['race']['cached']}、数据库 {row['race']['database']}，没有发布旧快照。")
        elif scenario=='refill-race':lines.append(f"{model} 确定性回填竞争：缓存 {row['cached']}、数据库 {row['database']}，stalePublished={row['stalePublished']}。")
        elif scenario=='parallel-proof':lines.append(f"{model} 两城市对 SQL 各注入 {row['injectedDelayMs']} ms 延迟，峰值并行加载 {row['peakConcurrentCityLoads']}，总耗时 {row['elapsedMs']:.2f} ms。该实验只证明锁粒度，不能作为自然 QPS 改善百分比。")
        elif scenario=='expiry':lines.append(f"{model} TTL 诊断：100 个测试 Key 的初始 TTL 分布 {row['initialTtlBinsSeconds']}；重建 SQL 的 100 ms 窗口峰值 {max(row['rebuildSqlPer100Ms'].values(),default=0)}，总重建 SQL {sum(row['rebuildSqlPer100Ms'].values())}。使用真实 Train 1 元数据，TTL 缩短为 10 秒，不能代表生产车次分布。")
        elif scenario=='pulses':
            latency=row['operations'].get('commit-to-correct',{})
            lines.append(f"{model} {row['commits']} 个停止后续写入的购票/取消脉冲：平均 {latency.get('meanMs',0):.2f} ms，P99 {latency.get('p99Ms',0):.2f} ms；活动区段 {row['activeSegments']}。连续三次与数据库相符才收敛，耗时包括数据库对账和轮询。")
        elif scenario=='unique-train':lines.append(f"{model} 5 秒高基数成本探针：临时车次空值 Key 数 {row.get('negativeTrainKeys','见原始文件')}。")
        elif scenario in ('mixed','overlap-mixed'):lines.append(f"{model} {scenario} 5 秒对账探针：版本 Key {row.get('versionKeys','见原始文件')}，活动区段 {row['activeSegments']}，待处理事件 {row['pendingEvents']}；额外归属字段见原始记录。")
    (DOCS/'FUNCTIONAL.json').write_text(json.dumps(proofs,ensure_ascii=False,indent=2),encoding='utf-8')
    lines+=['','单 Key 击穿分别执行 50 同时到达请求、30 波，原始文件保留加载/锁内命中和 SQL。空城市对 A 未缓存空结果，会在锁内连续加载；B 在每波同一 TTL 窗口内合并为一个成功加载。余票 A 原本已有加载锁，其合并能力不属于本轮新增收益。','',
    '失效事件按 orderSn 与原事务 commit 返回关联。连续混合期间的 commit→invalidation 与停止写入后的 commit→correct 分别报告，不将事件创建时间当作提交时间。故障仅注入隔离客户端和调用，验证 Redis 客户端异常、加载超时、持久化事件保留与恢复重试；没有停止共享 Redis/MySQL，不代替服务重启、网络分区或 CDC 故障实验。','',
    '## 交付、发布边界与剩余判断','','AUDIT.md 为缓存清单和指标口径，RUNBOOK.md 为复现和切换步骤，RESULTS.md/RESULTS.json 为逐轮正式数据，FUNCTIONAL.json 和 evidence 为正确性与原始证据。预热工具默认并发 2、10 OD/秒，复用正常加载器；没有生产周期性整车预热。','',
    '空值标记要求协调升级，旧实例不能同时读取新标记。遗留永久缓存由只读审计工具检查，显式 apply 仅为 TTL=-1 的票务缓存补 TTL，不延长已有 TTL；本轮只在测试前缀验证，不处理业务 Redis。源夹具存在历史冲突，报告保留；仅复制 Train 1 合法库存，未修复或迁移业务库。','',
    '可用于简历的是：识别并修复空结果穿透、加载锁粒度与失效后旧值回填，使用真实 Redis/MySQL、多轮 A/B 和故障注入验证。不能写成热点或混合链路全面提速，不能把服务内空查询 QPS 写成全链路购票 QPS，不能写成 Canal/MQ 已完成或余票强一致。']
    (DOCS/'REPORT.md').write_text('\n'.join(lines),encoding='utf-8')
    print('Wrote evidence narrative; review gate status and functional evidence before delivery.')
if __name__=='__main__':main()
