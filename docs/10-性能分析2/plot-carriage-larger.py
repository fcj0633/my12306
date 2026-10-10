"""Standalone figures for the current-version short-burst measurement."""
import json,pathlib
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

HERE=pathlib.Path(__file__).resolve().parent
groups=json.loads((HERE/'results/carriage-50-100/larger-summary.json').read_text('utf-8'))['groups']
rows=json.loads((HERE/'results/carriage-50-100/formal-matrix.json').read_text('utf-8'))
plt.rcParams['font.sans-serif']=['Microsoft YaHei','SimHei','DejaVu Sans']
plt.rcParams['axes.unicode_minus']=False
colors=['#346FA1','#008578']
fig,axes=plt.subplots(2,2,figsize=(14,10))
ax=axes[0,0]
for i,level in enumerate([50,100]):
    for enabled in [0,1]:
        x=i+(enabled-.5)*.3;g=groups[f'{level}-{enabled}']
        values=[r['successQps'] for r in rows if r['concurrency']==level and int(r['probeEnabled'])==enabled]
        ax.scatter([x-.045,x,x+.045],values,color=colors[enabled],s=48,label=['采集关闭','采集开启'][enabled] if i==0 else None)
        ax.plot([x-.09,x+.09],[g['qpsMedian']]*2,color=colors[enabled],lw=3)
        ax.text(x,g['qpsMedian']-2.6,f"{g['qpsMedian']:.2f}",ha='center',fontsize=10,color=colors[enabled])
ax.set_xticks([0,1],['50并发','100并发']);ax.set_ylim(0,35)
ax.set_ylabel('成功QPS（包含排空）');ax.set_title('三轮结果：点为各轮，横线为中位数');ax.legend(loc='upper left')
ax.grid(axis='y',alpha=.2)
ax=axes[0,1]
labels=['方法外差值','购票方法']
external=[groups[f'{level}-1']['http']['mean']-groups[f'{level}-1']['stages']['purchase.total']['mean'] for level in [50,100]]
method=[groups[f'{level}-1']['stages']['purchase.total']['mean'] for level in [50,100]]
ax.bar([0,1],method,color=colors[0],label='购票方法')
ax.bar([0,1],external,bottom=method,color='#C7D6E2',label='方法外差值（未定位）')
for i in [0,1]:
    ax.text(i,method[i]/2,f'{method[i]:.0f}ms',ha='center',color='white')
    ax.text(i,method[i]+external[i]/2,f'{external[i]:.0f}ms',ha='center')
ax.set_xticks([0,1],['50并发','100并发']);ax.set_ylabel('均值（ms）');ax.set_ylim(0,4400)
ax.set_title('开启采集：HTTP采样器与购票方法');ax.legend(loc='upper left');ax.grid(axis='y',alpha=.2)
ax=axes[1,0]
keys=['seat.lock-wait','tx.proxy','order.remote','seat.unlock-safe','jdbc.commit','jdbc.seat-select']
names=['车厢锁获取／等待','事务完整调用','远程建单','安全解锁','提交JDBC*','选座JDBC*']
y=np.arange(len(keys))
for i,level in enumerate([50,100]):
    values=[groups[f'{level}-1']['stages'][key]['mean'] for key in keys]
    ax.barh(y+(i-.5)*.34,values,height=.32,color=colors[i],label=f'{level}并发')
    for j,value in enumerate(values):ax.text(value+5,y[j]+(i-.5)*.34,f'{value:.1f}',va='center',fontsize=9)
ax.set_yticks(y,names);ax.invert_yaxis();ax.set_xlim(0,760);ax.set_xlabel('阶段均值（ms）')
ax.set_title('开启采集：关键阶段（*包含于事务，不能叠加）');ax.legend();ax.grid(axis='x',alpha=.2)
ax=axes[1,1]
keys=sorted(groups['50-1']['distribution']);x=np.arange(len(keys))
for i,level in enumerate([50,100]):
    values=[groups[f'{level}-1']['distribution'][key] for key in keys]
    ax.bar(x+(i-.5)*.36,values,width=.34,color=colors[i],label=f'{level}并发')
ax.set_xticks(x,keys);ax.set_ylabel('开启采集三轮成功分配张数');ax.set_xlabel('车厢编号');ax.set_ylim(0,42)
ax.set_title('分配均匀：均观测到9车厢并行，同key交叠为0');ax.legend();ax.grid(axis='y',alpha=.2)
fig.suptitle('车厢锁版本｜50与100并发｜810张原始库存｜2026-10-09',fontsize=17,y=.99)
fig.text(.5,.02,'共同发送窗口2秒，每轮每线程仅一笔请求；结果反映突发完成速率，不证明长期稳定容量。关闭采集仍加载代理。',ha='center',fontsize=10,color='#555555')
fig.tight_layout(rect=[0,.045,1,.96])
fig.savefig(HERE/'车厢锁优化-50与100并发表现.png',dpi=160)
plt.close(fig)
