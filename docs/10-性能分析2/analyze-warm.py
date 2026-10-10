"""Analyze only the authoritative same-window matrix; never select fastest rounds."""
import csv,datetime,importlib.util,json,os,pathlib,re,statistics
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('warm',HERE/'warm-runner.py');w=importlib.util.module_from_spec(spec);spec.loader.exec_module(w)
OUT=w.OUT
def read(path):return list(csv.DictReader(path.open(encoding='utf-8')))
def stats(values):
    return dict(mean=statistics.mean(values),median=statistics.median(values),min=min(values),max=max(values),n=len(values)) if values else {}
def stamp(value):return datetime.datetime.fromisoformat(re.sub(r'(\.\d{6})\d+',r'\1',value.replace('Z','+00:00'))).timestamp()*1000
def group(rows):
    stages={};distribution={};parallel=0;sql={};joins=[];requests=[];metadata_outside=[];pools={};counters={};metadata_count=0;metadata_seconds=0;held=[];handoff=[]
    logs=[]
    for path in OUT.glob('ticket*.stdout.log'):
        for line in path.read_text('utf-8',errors='replace').splitlines():
            match=re.search(r'\[([^\]]*9002-exec-\d+)\].*orderSn=(\d+)',line)
            if match:
                try:logs.append(dict(wall=stamp(line[:29].split(' ')[0]),thread=match[1],sn=match[2]))
                except ValueError:pass
    for row in rows:
        assert row['success']==row['sent'] and not row['capReached'] and row['success']<=405
        assert all(v==row['success'] for v in row['accounting'].values())
        req=read(OUT/(row['tag']+'-requests.csv'));requests.extend(req)
        name='my12306.purchase.metadata.prepare'
        metadata_count+=row['metricsAfter'].get(name,{}).get('COUNT',0)-row['metricsBefore'].get(name,{}).get('COUNT',0)
        metadata_seconds+=row['metricsAfter'].get(name,{}).get('TOTAL_TIME',0)-row['metricsBefore'].get(name,{}).get('TOTAL_TIME',0)
        for schema,field in [('ticket','digest'),('order','orderDigest')]:
            for key,after in row['dbAfter'].get(field,{}).items():
                before=row['dbBefore'].get(field,{}).get(key,dict(count=0,time=0,rows=0));count=after['count']-before['count']
                if count>0:
                    item=sql.setdefault(schema+'|'+after['text'],dict(schema=schema,text=after['text'],count=0,totalMs=0,rows=0))
                    item['count']+=count;item['totalMs']+=(after['time']-before['time'])/1e9;item['rows']+=after['rows']-before['rows']
        for service in ['ticket','order']:
            before=row['metricsBefore'] if service=='ticket' else row['extraBefore']['9003']
            after=row['metricsAfter'] if service=='ticket' else row['extraAfter']['9003']
            pool=pools.setdefault(service,dict(acquireCount=0,acquireSeconds=0,timeouts=0,pending=[]))
            name='hikaricp.connections.acquire'
            pool['acquireCount']+=after.get(name,{}).get('COUNT',0)-before.get(name,{}).get('COUNT',0)
            pool['acquireSeconds']+=after.get(name,{}).get('TOTAL_TIME',0)-before.get(name,{}).get('TOTAL_TIME',0)
            name='hikaricp.connections.timeout'
            pool['timeouts']+=after.get(name,{}).get('COUNT',0)-before.get(name,{}).get('COUNT',0)
            for sample in row['resources']:
                values=sample.get(service,{}) if service=='ticket' else sample.get('order',{}).get('9003',{})
                if row['startMs']/1000<=sample['time']<=row['endMs']/1000 and 'hikaricp.connections.pending' in values:
                    pool['pending'].append(values['hikaricp.connections.pending'].get('VALUE',0))
        for name in ['attempt','fast-success','candidate-miss','fallback','db-retry']:
            key='my12306.purchase.carriage.'+name
            counters[name]=counters.get(name,0)+row['metricsAfter'].get(key,{}).get('COUNT',0)-row['metricsBefore'].get(key,{}).get('COUNT',0)
        if not row['capture']:continue
        traces=read(OUT/(row['tag']+'-traces.csv'));locks=read(OUT/(row['tag']+'-locks.csv'));by_id={};intervals=[]
        for trace in traces:
            by_id.setdefault(trace['id'],{})[trace['stage']]=trace
            stages.setdefault(trace['stage'],[]).append(int(trace['durationNs'])/1e6)
        roots=[item['purchase.total'] for item in by_id.values()]
        for root in roots:
            begin=float(root['wallMs']);end=begin+(int(root['endNs'])-int(root['startNs']))/1e6
            matching=[r for r in logs if root['thread'].endswith(r['thread'].strip()) and begin-2<=r['wall']<=end+2]
            sns={r['sn'] for r in matching}
            if len(sns)==1:
                sn=next(iter(sns));client=next((r for r in req if r['orderSn']==sn),None)
                if client:joins.append(dict(tag=row['tag'],requestId=client['requestId'],orderSn=sn,traceId=root['id'],
                    httpMs=int(client['httpNs'])/1e6,methodMs=int(root['durationNs'])/1e6,
                    beforeMethodMs=begin-float(client['sendMs']),afterMethodMs=float(client['completeMs'])-end))
        for item in by_id.values():
            if int(item['tx.proxy']['count'])==1:
                body,proxy=item['tx.body'],item['tx.proxy']
                resource_events=[event for event in locks if event['id']==item['purchase.total']['id']]
                acquired=max(int(event['endNs']) for event in resource_events if event['stage']=='seat.lock-wait')
                releasing=min(int(event['startNs']) for event in resource_events if event['stage']=='seat.unlock-safe')
                assert acquired<=int(proxy['stageStartNs'])<=int(proxy['stageEndNs'])<=releasing
                assert int(item['jdbc.commit']['stageEndNs'])<=int(proxy['stageEndNs'])
                stages.setdefault('tx.begin',[]).append((int(body['stageStartNs'])-int(proxy['stageStartNs']))/1e6)
                stages.setdefault('tx.finish',[]).append((int(proxy['stageEndNs'])-int(body['stageEndNs']))/1e6)
            # Metadata JDBC has completed before the first user/resource lock.
            if row['version']=='candidate':
                first=min(int(event['startNs']) for event in locks if event['id']==item['purchase.total']['id'] and event['stage'].endswith('lock-wait'))
                checks=[int(item[name]['stageEndNs'])<=first for name in ['jdbc.station-relation','jdbc.price'] if name in item]
                metadata_outside.extend(checks)
        for acquisition in [event for event in locks if event['stage']=='seat.lock-wait' and event['failed']=='false']:
            safe=next((event for event in locks if event['id']==acquisition['id'] and event['key']==acquisition['key'] and event['stage']=='seat.unlock-safe' and int(event['startNs'])>=int(acquisition['endNs'])),None)
            assert safe,'Missing lock release'
            intervals.append((acquisition['key'],int(acquisition['endNs']),int(safe['startNs'])))
            carriage=acquisition['key'].rsplit('_',1)[-1];distribution[carriage]=distribution.get(carriage,0)+1
        for key in {item[0] for item in intervals}:
            sequence=sorted([item for item in intervals if item[0]==key],key=lambda item:item[1])
            assert all(a[2]<=b[1] for a,b in zip(sequence,sequence[1:])), 'Same resource lock overlap'
            held.extend((item[2]-item[1])/1e6 for item in sequence)
            handoff.extend((b[1]-a[2])/1e6 for a,b in zip(sequence,sequence[1:]))
        active=0
        for _,delta in sorted([(x[1],1) for x in intervals]+[(x[2],-1) for x in intervals]):
            active+=delta;parallel=max(parallel,active)
    for pool in pools.values():
        pool['acquireMeanMs']=pool['acquireSeconds']*1000/pool['acquireCount'] if pool['acquireCount'] else None
        pool['maxSampledPending']=max(pool['pending'],default=None)
    for item in sql.values():item['meanMs']=item['totalMs']/item['count']
    if rows[0]['capture']:
        assert len(joins)==sum(row['success'] for row in rows),'Every successful request must join by order number'
        if rows[0]['version']=='candidate':
            assert len(metadata_outside)==2*sum(row['success'] for row in rows) and all(metadata_outside)
    return dict(qps=stats([r['successQps'] for r in rows]),p95=stats([r['p95'] for r in rows]),p99=stats([r['p99'] for r in rows]),
        success=sum(r['success'] for r in rows),sent=[r['sent'] for r in rows],drain=stats([r['drainSeconds'] for r in rows]),
        http=stats([int(r['httpNs'])/1e6 for r in requests]),classification=stats([int(r['classifyNs'])/1e6 for r in requests]),
        windowCompletionQps=stats([r['completedInWindow']/r['window'] for r in rows]),
        workerMin=min(min(r['workerCounts'].values()) for r in rows),workerMax=max(max(r['workerCounts'].values()) for r in rows),
        stages={name:stats(values) for name,values in stages.items()},pools=pools,sql=list(sql.values()),counters=counters,
        maxParallel=parallel,distribution=distribution,metadataChecks=len(metadata_outside),metadataAllOutside=all(metadata_outside),joins=joins,
        metadataPrepareMeanMs=metadata_seconds*1000/metadata_count if metadata_count else None,lockHeld=stats(held),observedHandoff=stats(handoff))
def main():
    rows=json.loads((OUT/'comparison-matrix.json').read_text('utf-8'));assert len(rows)==36
    groups={}
    for version in ['baseline','candidate']:
        for level in [20,50,100]:
            for capture in [False,True]:
                selected=[row for row in rows if row['version']==version and row['level']==level and row['capture']==capture]
                assert len(selected)==3
                groups[f'{version}-{level}-{int(capture)}']=group(selected)
    w.dump(OUT/'warm-summary.json',dict(groups=groups,rounds=36,window=rows[0]['window'],success=sum(r['success'] for r in rows)))
    for key,g in groups.items():print(key,round(g['qps']['median'],2),[round(r['successQps'],2) for r in rows if f"{r['version']}-{r['level']}-{int(r['capture'])}"==key], 'joined',len(g['joins']))
if __name__=='__main__':main()
