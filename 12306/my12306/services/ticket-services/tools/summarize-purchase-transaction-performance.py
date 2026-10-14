"""Archive and summarize the complete reservation transaction benchmark (50 buyers, one ticket each)."""
import json
from pathlib import Path
from statistics import median
import shutil

MODULE = Path(__file__).resolve().parents[1]
DOCS = MODULE.parents[3] / 'docs' / '11-segment-inventory'
SOURCE = MODULE / 'target' / 'segment-validation'


def main():
    report = json.loads((SOURCE / 'purchase-transaction-performance.json').read_text(encoding='utf-8'))
    evidence = DOCS / 'evidence'
    evidence.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SOURCE / 'purchase-transaction-performance.json', evidence / 'purchase-transaction-performance.json')
    shutil.copyfile(SOURCE / 'run.json', evidence / 'purchase-transaction-tests.json')
    lines = [
        '# 完整占座主事务性能：50 并发、每笔 1 张票', '',
        '测试日期：2026-10-10。使用验证器创建的隔离 MySQL 数据库，配置业务库只读；测试结束删除隔离库。', '',
        '计时从调用 Spring 事务代理之前开始，到代理返回之后结束，包括连接获取、真实车厢选座、车票插入、库存条件更新、订单及补偿 DTO 构造、事务提交。区段版本还包含真实的缓存失效事件插入和事务同步回调。', '',
        '异步事件处理器在本测试中替换为空实现，保留持久化事件，不执行任务投递、Redis 删除或异步事件清理。车厢锁获取、令牌准入、HTTP、远程乘客查询和远程建单均在本次测量范围之外。', '',
        '旧版本事务和选座器从改造前 HEAD 复制到测试专用类，保留原事务逻辑、MyBatis 查询与条件更新，通过 Spring 事务代理执行。基线使用旧 OD 表，新版本使用当前真实服务和区段表。两个版本共用 TicketMapper、MySQL、连接池和客户端线程池。', '',
        '固定 50 个并发工作者，连接池上限 20。五个站、1,200 个物理座位，按 50 个独立候选车厢分组，每厢 24 座。每个工作者仅操作自己的车厢，因此不产生同座竞争；该人工布局用于隔离主事务成本，不能代表真实列车的车厢锁竞争。', '',
        '每批每个工作者连续购买 20 次、每次 1 票，共 1,000 笔。每批开始库存为空，结束各车厢占用 20/24（83.3%），并非全程维持低占用。每个版本、区段长度、轮次包含 10 批；库存重置和对账位于计时窗口之外。预热两遍全部六种组合，共 2,400 笔。随后运行三轮，版本顺序交替，共 180,000 笔计时事务。', '',
        '每批核对车票数量、库存占用数和新区段车票/订单归属；新版还核对物理座位无重复和每笔一条持久化事件。全部计时请求成功，无重试、拒绝或回滚；测试通过，失败、错误和跳过均为 0。', '',
        'QPS = 成功事务数 / 各批实际购票窗口耗时之和，包含工作者循环与调用开销，不包含批间重置、对账和预热。它是分批压测的主事务完成速率，不能称为持续购票接口 QPS。', '',
        '## 三轮中位数', '',
        '下表分别对各轮指标取中位数，P95/P99 不是三轮所有样本合并后的分位数。', '',
        '| 区段数 | 旧版均值 ms | 新版均值 ms | 均值变化 | 旧版 P95 ms | 新版 P95 ms | 旧版 P99 ms | 新版 P99 ms | 旧版事务 QPS | 新版事务 QPS | QPS 变化 |',
        '|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|',
    ]
    groups = {}
    for k in (1, 2, 4):
        for model in ('legacy-od', 'segment'):
            groups[k, model] = [x for x in report['results'] if x['segments'] == k and x['model'] == model]
        old, new = groups[k, 'legacy-od'], groups[k, 'segment']
        def value(xs, key):
            return median(x[key] for x in xs)
        om, nm = value(old, 'meanMs'), value(new, 'meanMs')
        oq, nq = value(old, 'successfulTransactionsPerSecond'), value(new, 'successfulTransactionsPerSecond')
        lines.append(f"| {k} | {om:.2f} | {nm:.2f} | {(nm / om - 1) * 100:+.1f}% | {value(old, 'p95Ms'):.2f} | {value(new, 'p95Ms'):.2f} | {value(old, 'p99Ms'):.2f} | {value(new, 'p99Ms'):.2f} | {oq:.0f} | {nq:.0f} | {(nq / oq - 1) * 100:+.1f}% |")
    lines += ['', '## 三轮范围', '',
              '| 区段数 | 模型 | 均值 ms 范围 | P99 ms 范围 | 事务 QPS 范围 | 活跃计时秒数范围 |',
              '|---:|---|---:|---:|---:|---:|']
    for (k, model), xs in groups.items():
        ranges = [f"{min(x[key] for x in xs):.2f}–{max(x[key] for x in xs):.2f}" for key in ('meanMs', 'p99Ms', 'successfulTransactionsPerSecond', 'elapsedSeconds')]
        lines.append(f"| {k} | {model} | " + ' | '.join(ranges) + ' |')
    lines += ['', '## 每轮数据', '',
              '| 轮次 | 区段数 | 模型 | 成功数 | 均值 ms | P50 ms | P95 ms | P99 ms | 事务 QPS |',
              '|---:|---:|---|---:|---:|---:|---:|---:|---:|']
    for x in report['results']:
        lines.append(f"| {x['round']} | {x['segments']} | {x['model']} | {x['success']} | {x['meanMs']:.2f} | {x['p50Ms']:.2f} | {x['p95Ms']:.2f} | {x['p99Ms']:.2f} | {x['successfulTransactionsPerSecond']:.0f} |")
    lines += ['', '## 解释与边界', '',
              '- 新模型一次购票更新 1/2/4 条区段；旧模型均更新一条 OD。新版额外维护库存归属索引，并写入一条缓存失效事件。两臂均插入一张车票。',
              '- 选座是本次主事务计时的一部分；同车厢已售座位逐渐增加，区段版需要验证同一座位全程可用。',
              '- 延迟包含连接池等待、数据库提交以及本机调度/GC影响。未做逐阶段因果归因，不能把差值全部归因于区段行数。',
              '- 短中长区间三组是独立测量，不能要求它们的耗时严格单调。三轮范围是本次环境下的波动，不能外推为生产容量。',
              '- 本测试没有同座竞争或库存不足，结果不含快速拒绝抬高吞吐的情况，也没有测取消、回调或整条购票链路。', '',
              '逐事务样本、每批统计和环境说明见 [原始性能数据](evidence/purchase-transaction-performance.json)；执行结果见 [测试记录](evidence/purchase-transaction-tests.json)。', '',
              '复现：在票务模块目录执行 `python tools/validate-segment-inventory.py --tests PurchaseTransactionPerformanceTest --benchmark`，完成后执行 `python tools/summarize-purchase-transaction-performance.py`。验证器只写入其自己创建的隔离数据库。', '']
    (DOCS / 'PURCHASE-TRANSACTION-PERFORMANCE.md').write_text('\n'.join(lines), encoding='utf-8')
    print('Archived complete transaction benchmark: ' + str(DOCS / 'PURCHASE-TRANSACTION-PERFORMANCE.md'))


if __name__ == '__main__':
    main()
