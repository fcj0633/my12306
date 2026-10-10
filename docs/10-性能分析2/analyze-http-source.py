"""Correlate client order numbers with existing business traces and lock logs."""
import csv,datetime,json,pathlib,re,statistics
HERE=pathlib.Path(__file__).resolve().parent;OUT=HERE/'results/http-business'
def read(path):return list(csv.DictReader(path.open(encoding='utf-8')))
def analyze():
    logs=[]
    for line in (OUT/'ticket.stdout.log').read_text('utf-8',errors='replace').splitlines():
        if '购票锁已获取' not in line:continue
        match=re.search(r'\[([^\]]*9002-exec-\d+)\].*orderSn=(\d+)',line)
        if not match:continue
        logs.append(dict(thread=match[1].strip(),sn=match[2],wall=datetime.datetime.fromisoformat(line[:29]).timestamp()*1000))
    summaries=[];matched_records=[]
    for round in json.loads((OUT/'diagnostic-rounds.json').read_text('utf-8')):
        traces=read(OUT/(round['tag']+'-traces.csv'));roots=[r for r in traces if r['stage']=='purchase.total']
        order_trace={}
        for root in roots:
            end=float(root['wallMs'])+(int(root['endNs'])-int(root['startNs']))/1e6
            matches=[log for log in logs if root['thread'].endswith(log['thread']) and float(root['wallMs'])-2<=log['wall']<=end+2]
            assert len(matches)==1,(root['id'],matches)
            order_trace[matches[0]['sn']]=root
        data=read(OUT/f"s1-{round['concurrency']}-{round['tag']}.jtl")
        assert set(r['diagOrderSn'] for r in data)==set(order_trace)
        for sequence in ['1','2']:
            selected=[r for r in data if r['diagSeq']==sequence];values=[];stages={}
            for sample in selected:
                root=order_trace[sample['diagOrderSn']];method=float(root['durationNs'])/1e6
                serverEnd=float(root['wallMs'])+(int(root['endNs'])-int(root['startNs']))/1e6
                item=dict(level=round['concurrency'],sequence=sequence,orderSn=sample['diagOrderSn'],traceId=root['id'],
                    samplerMs=float(sample['elapsed']),sendMs=float(sample['diagSendMs']),postMs=float(sample['diagPostMs']),methodMs=method,
                    beforeMethodMs=float(root['wallMs'])-float(sample['diagSendStartMs']),afterMethodMs=float(sample['diagSendEndMs'])-serverEnd)
                values.append(item);matched_records.append(item)
                for trace in [t for t in traces if t['id']==root['id']]:stages.setdefault(trace['stage'],[]).append(float(trace['durationNs'])/1e6)
            summaries.append(dict(level=round['concurrency'],sequence=sequence,count=len(selected),
                means={key:statistics.mean(v[key] for v in values) for key in ['samplerMs','sendMs','postMs','methodMs','beforeMethodMs','afterMethodMs']},
                stages={key:statistics.mean(v) for key,v in stages.items()}))
    (OUT/'correlated-requests.json').write_text(json.dumps(matched_records,indent=2),'utf-8')
    (OUT/'correlated-summary.json').write_text(json.dumps(summaries,indent=2),'utf-8')
    print(json.dumps(summaries,ensure_ascii=False))
if __name__=='__main__':analyze()
