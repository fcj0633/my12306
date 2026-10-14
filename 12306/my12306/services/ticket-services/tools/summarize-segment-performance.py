"""Publish the isolated SQL experiment and test summaries without copying credentials or test logs."""
import json
from pathlib import Path
import shutil
import statistics

MODULE=Path(__file__).resolve().parents[1]
ROOT=MODULE.parents[3]
SOURCE=MODULE/'target/segment-validation'
OUTPUT=ROOT/'docs/11-segment-inventory'


def main():
    experiment=json.loads((SOURCE/'performance.json').read_text(encoding='utf-8'))
    run=json.loads((SOURCE/'run.json').read_text(encoding='utf-8'))
    results=experiment['results']
    evidence=OUTPUT/'evidence'
    evidence.mkdir(parents=True,exist_ok=True)
    for source,target in [('performance.json','performance.json'),('run.json','tests.json'),('migration.json','test-migration.json')]:
        shutil.copyfile(SOURCE/source,evidence/target)

    def group(kind,state,k,passengers,concurrency,arm):
        rows=[r for r in results if r['kind']==kind and r['state']==state and r['segments']==k
              and r['passengers']==passengers and r['concurrency']==concurrency and r['arm']==arm]
        assert len(rows)==3
        return rows

    def median(rows,key):return statistics.median(r[key] for r in rows)
    def interval(rows,key):return f"{min(r[key] for r in rows):.2f}–{max(r[key] for r in rows):.2f}"

    lines=['# 区段库存 SQL 性能对照与验收结果','',
           '测试日期：2026-10-10。所有数据写入和状态重置均在验证器创建的独立 MySQL 数据库中完成，配置业务库只读。', '',
           f"最终回归：**{run['totals']['tests']} 个测试，失败 {run['totals']['failures']}，错误 {run['totals']['errors']}，跳过 {run['totals']['skipped']}**；其中包含一次完整性能实验。票务模块及其父工程打包通过。", '',
           f"性能实验共 **{len(results)} 组、{sum(r['samples'] for r in results):,} 个样本**。原始逐组数据和执行计划见 [performance.json](evidence/performance.json)，测试汇总见 [tests.json](evidence/tests.json)，独立库迁移对账见 [test-migration.json](evidence/test-migration.json)。", '',
           '## 测量范围与基线', '',
           '- 查询使用现有三列投影、排序和 LIMIT，比较预计算 OD 表与真实区段选座 SQL。每个场景、负载均运行三轮，两臂顺序交替，查询臂每轮先预热 20 次。',
           '- 基线 OD 状态事先由同一份区段状态重算，保证两臂返回座位数量一致。重算 OD 的成本未计入查询基线，因此比较的是已物化 OD 的读取成本，不能据此比较完整库存维护成本。',
           '- 低占用场景二等座每段均有 810 个空闲座位；高占用场景每段有 81 个。碎片化场景每段仍有 607–608 个空闲座位，但每个物理座位恰有一个区段被占用，覆盖四段的直达余票为零。分布按物理座位序号生成，不按 Snowflake ID 的余数生成。',
           '- 覆盖 1/2/4 个基本区段、1/5 人、20/50/100 并发，数据库连接池两臂均为 20。查询每工作线程重复三次；库存事务每线程重复两次。',
           '- 写入实验包含连接获取、已选座位可用性校验、条件库存更新及提交，使用每线程独立的座位集。恢复测试库存的操作不计入单次事务延迟；不发布写入 QPS，避免把清理时间混入购票吞吐。',
           '- 写入实验不包含逐乘客车票插入、归属 CASE 分配、缓存失效事件、Redis/Lua、车厢锁、HTTP、远程建单及真实跨实例故障。短窗口和本机资源波动会影响结果，以下数值不是生产容量保证。', '',
           '## 查询结果', '',
           '下面统一取 50 并发、5 人；数值为三轮中位数。完整 20/50/100 并发和单人结果在原始数据中。QPS 是查询完成速率，包含返回不足或零座位的查询，不是成功售票 QPS。', '',
           '| 库存状态 | 区段数 | OD 平均 ms | 区段平均 ms | OD P99 ms | 区段 P99 ms | OD 查询 QPS | 区段查询 QPS |',
           '|---|---:|---:|---:|---:|---:|---:|---:|']
    labels={'low':'低占用','high':'90% 占用','fragmented':'碎片化'}
    for state in ['low','high','fragmented']:
        for k in [1,2,4]:
            a=group('query',state,k,5,50,'baseline');b=group('query',state,k,5,50,'segment')
            lines.append(f"| {labels[state]} | {k} | {median(a,'meanMs'):.2f} | {median(b,'meanMs'):.2f} | {median(a,'p99Ms'):.2f} | {median(b,'p99Ms'):.2f} | {median(a,'queryQps'):.0f} | {median(b,'queryQps'):.0f} |")
    lines += ['', '四段碎片化库存下，没有贯穿全程的座位。区段 SQL 需要验证大量候选，而已物化 OD 索引可以直接找到无可售记录。这是本方案最明显的读取代价。执行计划显示对 810 个二等座候选逐一执行区段检查；普通低占用 LIMIT 1 场景只需检查首个符合条件的座位。', '',
              '相关场景三轮平均耗时范围如下，避免把单个中位数当作稳定值：', '',
              '| 50 并发、5 人、4 段 | OD 平均耗时范围 ms | 区段平均耗时范围 ms |', '|---|---:|---:|']
    for state in ['low','high','fragmented']:
        lines.append(f"| {labels[state]} | {interval(group('query',state,4,5,50,'baseline'),'meanMs')} | {interval(group('query',state,4,5,50,'segment'),'meanMs')} |")
    lines += ['', '## 条件库存事务结果', '',
              '下表取 5 人、低占用，仍为三轮中位数。相比 OD 更新 5 行，区段更新分别为 5、10、20 行。选中座位在各线程之间不重叠，因此不包含争抢同一座位的额外成本。', '',
              '| 并发 | 区段数 | OD 平均 ms | 区段平均 ms | OD P99 ms | 区段 P99 ms | OD 更新行数 | 区段更新行数 |',
              '|---:|---:|---:|---:|---:|---:|---:|---:|']
    for concurrency in [20,50,100]:
        for k in [1,2,4]:
            a=group('inventoryTransaction','low',k,5,concurrency,'baseline');b=group('inventoryTransaction','low',k,5,concurrency,'segment')
            lines.append(f"| {concurrency} | {k} | {median(a,'meanMs'):.2f} | {median(b,'meanMs'):.2f} | {median(a,'p99Ms'):.2f} | {median(b,'p99Ms'):.2f} | 5 | {5*k} |")
    lines += ['', '较长区间会增加行锁、归属索引维护和提交负担。真实购票保留车厢锁，事务变长还可能放大同车厢请求等待。表中包含本机提交和共享连接池排队，不能把耗时差全部解释为数据库执行行数增加。', '',
              '## 存储与缓存代价', '',
              '- 五站样例原 OD 表有 9,600 行，新库存为 960 个物理座位、3,840 个基本区段，另有 5 条站序投影。旧表暂时保留，所以切换后磁盘不会立即释放；归档旧表后，库存事实行数才体现减少。',
              '- 一般而言，完整 OD 表为 S×n(n−1)/2 条库存记录，区段模型为 S×(n−1) 条区段记录加 S 条物理座位。原项目按乘客×重叠 OD 逐次更新，区段模型按席别批量更新 p×k 行，避免重叠 OD 的同步写放大。',
              '- 准入 Lua 的字段检查和扣减由席别数 t 扩大到 t×k；整车桶被失败或取消失效后，需要重新聚合所有基本区段。频繁失败、取消和碎片化准入会增加桶重建压力。',
              '- 每次成功占座或实际取消额外写一条失效事件；重叠 OD 缓存删除和事件清理由有界后台处理，减少车厢锁内工作，但增加后台 Redis、数据库请求和最终一致性窗口。', '',
              '## 验收边界与后续测量', '',
              '已通过真实数据库事务、无分布式锁的重叠竞争、支付取消竞争、迁移与回滚，以及真实 Redis Lua 测试。当前业务库的静态数据冲突仍阻止正式迁移，见 [迁移检查](evidence/source-check.json)。', '',
              '本次没有启动新的生产服务或向业务库发起压测交易。完整 HTTP 成功购票 QPS、车厢锁等待、Lua 重建压力、远程订单耗时以及多票务实例联合压测尚未测量；这部分必须在全部服务隔离、数据冲突已修复后继续验收。不能用上述查询 QPS替代它们。', '',
              '后续完整链路实验需要分别报告有效售票成功、库存不足拒绝、令牌放行后选座不足和其他失败；按每个物理座位、基本区段核对所有有效车票归属。多实例分布式锁行为和服务故障恢复的端到端验收也仍需在隔离服务环境完成。', '']
    (OUTPUT/'PERFORMANCE.md').write_text('\n'.join(lines),encoding='utf-8')
    print('Published: '+str(OUTPUT/'PERFORMANCE.md'))


if __name__=='__main__':main()
