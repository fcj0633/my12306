"""Standalone measured-performance figure, current implementation only."""
import json,pathlib,statistics
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
HERE=pathlib.Path(__file__).resolve().parent;OUT=HERE/'results/carriage'
rows=json.loads((OUT/'formal-matrix.json').read_text('utf-8'))
summary=json.loads((OUT/'stage-summary.json').read_text('utf-8'))
plt.rcParams['font.sans-serif']=['Microsoft YaHei','SimHei','DejaVu Sans']
plt.rcParams['axes.unicode_minus']=False
fig,axes=plt.subplots(1,3,figsize=(16,5.4),gridspec_kw={'width_ratios':[1,1.5,1.1]})
colors=['#3B6C9E','#008777']
for i,enabled in enumerate([False,True]):
    values=[row['successQps'] for row in rows if row['probeEnabled']==enabled]
    axes[0].scatter([i-.08,i,i+.08],values,color=colors[i],s=60,zorder=3)
    median=statistics.median(values);axes[0].plot([i-.22,i+.22],[median,median],color=colors[i],lw=3)
    axes[0].text(i,median+7,f'中位数 {median:.2f}',ha='center',fontsize=10)
axes[0].set_xticks([0,1],['采集关闭','采集开启']);axes[0].set_ylim(0,200);axes[0].set_ylabel('成功 QPS');axes[0].set_title('20并发：三轮全部结果');axes[0].grid(axis='y',alpha=.2)
labels=['远程建单','事务完整调用','乘车人远程查询','车厢锁获取／等待','车厢锁安全释放','令牌准入']
keys=['order.remote','tx.proxy','passenger.remote','seat.lock-wait','seat.unlock-safe','token.admission']
values=[summary['stages'][key]['meanMs'] for key in keys]
axes[1].barh(labels[::-1],values[::-1],color='#3B6C9E')
for i,value in enumerate(values[::-1]):axes[1].text(value+.3,i,f'{value:.2f}',va='center',fontsize=10)
axes[1].set_xlim(0,max(values)*1.3);axes[1].set_xlabel('平均耗时（ms）');axes[1].set_title('开启采集：791次请求的阶段均值')
axes[1].grid(axis='x',alpha=.2)
distribution=summary['distribution']
axes[2].bar(list(sorted(distribution)),[distribution[key] for key in sorted(distribution)],color='#008777')
axes[2].set_xlabel('车厢编号');axes[2].set_ylabel('成功分配张数');axes[2].set_title('六轮：9个车厢分配分布');axes[2].set_ylim(0,max(distribution.values())*1.22)
for i,key in enumerate(sorted(distribution)):axes[2].text(i,distribution[key]+3,str(distribution[key]),ha='center',fontsize=9)
fig.suptitle('车厢锁实现｜原810张库存｜单Ticket实例｜2026-10-09',fontsize=16,y=.99)
fig.text(.5,.015,'正式窗口2秒；每轮恢复810张库存。阶段有包含关系，不能重复相加；历史数据不参与改善率计算。',ha='center',fontsize=10,color='#555555')
fig.tight_layout(rect=[0,.065,1,.93]);fig.savefig(HERE/'车厢锁优化-20并发表现.png',dpi=160);plt.close(fig)
