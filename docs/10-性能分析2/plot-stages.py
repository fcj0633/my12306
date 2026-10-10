import json,pathlib
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
HERE=pathlib.Path(__file__).resolve().parent
r=json.loads((HERE/'results/stages/stage-summary.json').read_text('utf-8'))['shared/True']
s=r['stages'];d=r['derived']
fig,axes=plt.subplots(3,1,figsize=(10,11),layout='constrained',gridspec_kw={'height_ratios':[3,2.4,1.1]})
outer=[('Chain validation','chain.validation'),('Token admission','token.admission'),('Passenger remote','passenger.remote'),
 ('User lock acquire','user.lock-wait'),('Seat lock acquire / wait','seat.lock-wait'),('Transaction proxy','tx.proxy'),
 ('Seat unlock (including owner check)','seat.unlock-safe'),('User unlock','user.unlock-safe'),('Remaining-cache eviction','remaining-cache.evict'),('Order remote','order.remote')]
values=[s[k]['mean'] for _,k in outer]
axes[0].barh([v[0] for v in outer],values,color=['#e58b36' if k=='seat.lock-wait' else '#287cb4' for _,k in outer])
axes[0].invert_yaxis();axes[0].set_xscale('log');axes[0].set_xlabel('Mean elapsed time (ms, logarithmic scale)')
axes[0].set_title('Request stages (exclusive outer phases; small orchestration overhead omitted)')
for index,v in enumerate(values):axes[0].text(v*1.06,index,f'{v:.2f}',va='center',fontsize=9)
axes[0].set_xlim(.8,max(values)*2.2)
inside=[('Train cache','train.cache'),('Station relation JDBC','jdbc.station-relation'),('Full seat allocation','seat.allocate'),
 ('Conditional seat update JDBC','jdbc.seat-update'),('Full price lookup','price.lookup'),('Ticket insert JDBC','jdbc.ticket-insert'),('Remaining method work','tx.body.other')]
iv=[(d if k in d else s)[k]['mean'] for _,k in inside]
axes[1].barh([v[0] for v in inside],iv,color='#287cb4');axes[1].invert_yaxis();axes[1].set_xlabel('Mean elapsed time (ms)')
axes[1].set_title(f"Inside transaction body: {s['tx.body']['mean']:.2f} ms; JDBC commit separately: {s['jdbc.commit']['mean']:.2f} ms")
for index,v in enumerate(iv):axes[1].text(v+.025,index,f'{v:.2f}',va='center',fontsize=9)
axes[1].set_xlim(0,max(iv)*1.23)
segments=[('Other before unlock',d['seat.acquired-to-unlock-start']['mean']-s['tx.proxy']['mean'],'#8d99a6'),
 ('Transaction proxy',s['tx.proxy']['mean'],'#287cb4'),('Release + handoff',r['handoff']['mean'],'#e58b36')]
left=0
for label,value,color in segments:
 axes[2].barh(['One key'],[value],left=left,color=color,label=f'{label}: {value:.2f} ms');left+=value
axes[2].set_xlabel('Mean serial interval (ms)');axes[2].set_title(f"Observed successive lock acquisitions: {r['lockCycle']['mean']:.2f} ms (~{1000/r['lockCycle']['mean']:.1f}/s)")
axes[2].legend(loc='upper left',bbox_to_anchor=(0,-.7),fontsize=9)
for ax in axes:ax.grid(axis='x',alpha=.25);ax.set_axisbelow(True)
fig.suptitle('20 clients | current version | 810 seats | request-scoped stage diagnostics')
fig.savefig(HERE/'20并发阶段耗时.png',dpi=160)
