"""Run after scoped transaction/task cleanup and owned service shutdown."""
import datetime,hashlib,importlib.util,json,pathlib,re,xml.etree.ElementTree as ET
HERE=pathlib.Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('warm',HERE/'warm-runner.py');w=importlib.util.module_from_spec(s);s.loader.exec_module(w)
OUT=w.OUT
def stamp(value):return datetime.datetime.fromisoformat(re.sub(r'(\.\d{6})\d+',r'\1',value.replace('Z','+00:00'))).timestamp()*1000
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def gc_summary(rows):
    starts=json.loads((OUT/'process-start-times.json').read_text('utf-8-sig'))
    deployments=sorted([json.loads(path.read_text('utf-8-sig')) for path in OUT.glob('ticket-deployment-*.json')],key=lambda x:stamp(x['CreationDate']))
    injector=json.loads((OUT/'injector-start-time.json').read_text('utf-8-sig'))
    summary=[]
    for row in rows:
        for service in ['ticket','user','order','gateway','nacos','injector']:
            if service=='ticket':
                deployment=max((x for x in deployments if stamp(x['CreationDate'])<=row['startMs']),key=lambda x:stamp(x['CreationDate']))
                following=next((x for x in deployments if stamp(x['CreationDate'])>stamp(deployment['CreationDate'])),None)
                path=OUT/'deployments'/('before-'+following['label'])/'ticket.gc.log' if following else OUT/'ticket.gc.log'
                created=stamp(deployment['CreationDate'])
            else:
                path=OUT/(service+'.gc.log');created=stamp(injector['CreationDate']) if service=='injector' else stamp(next(x for x in starts if x['service']==service)['CreationDate'])
            pauses=[]
            for line in path.read_text('utf-8',errors='replace').splitlines():
                match=re.match(r'\[(\d+(?:\.\d+)?)s\].*Pause.* (\d+(?:\.\d+)?)ms$',line)
                if not match:continue
                end=created+float(match[1])*1000;duration=float(match[2])
                if end>=row['startMs'] and end-duration<=row['endMs']:
                    pauses.append(dict(duration=duration,full='Pause Full' in line,kind=line))
            summary.append(dict(tag=row['tag'],service=service,pauses=pauses,count=len(pauses),
                fullCount=sum(x['full'] for x in pauses),totalMs=sum(x['duration'] for x in pauses),maxMs=max((x['duration'] for x in pauses),default=0)))
    return summary
def main():
    rows=json.loads((OUT/'comparison-matrix.json').read_text('utf-8'));assert len(rows)==36
    cleanups=[json.loads((OUT/(row['tag']+'-cleanup.json')).read_text('utf-8')) for row in rows]
    assert all(cleanup['orders']==row['success'] and cleanup['removedDelayTasks']==row['success'] and
               cleanup['remainingOwnedDelayTasks']==0 and cleanup['availableSeats']==810 and int(cleanup['token'])==810
               for row,cleanup in zip(rows,cleanups))
    env=json.loads((OUT/'environment.json').read_text('utf-8'));w.m.inventory_clean()
    assert w.c.base.sql('SELECT id,seat_status FROM 12306_ticket.t_seat WHERE NOT ('+w.c.base.POOL+') ORDER BY id;')==env['outsidePool']
    assert sorted(w.c.base.ticket_ids())==env['initialPerfTicketIds']
    for service in ['user','order','gateway']:
        path=w.c.ROOT/f'12306/my12306/services/{service}-services/target/{service}-services-0.0.1-SNAPSHOT.jar'
        assert sha(path)==env['artifactSha256'][service]
    baseline=OUT/'baseline/ticket-services-0.0.1-SNAPSHOT.jar';candidate=OUT/'candidate/ticket-services-0.0.1-SNAPSHOT.jar'
    assert sha(baseline)==env['artifactSha256']['ticket']
    assert sha(candidate)==sha(w.c.ROOT/'12306/my12306/services/ticket-services/target/ticket-services-0.0.1-SNAPSHOT.jar')
    old=json.loads((HERE/'results/warm-purchase/baseline/source-manifest.json').read_text('utf-8'));changed=[]
    for path,expected in old.items():
        if sha(w.c.ROOT/path)!=expected:changed.append(path)
    assert set(path.rsplit('/',1)[-1] for path in changed)=={'PurchaseTicketServiceImpl.java','PurchaseTicketTxService.java',
        'PurchaseTicketOrchestrationTest.java','PurchaseTicketServiceTest.java','TicketCallbackServiceTest.java'}
    owned=json.loads((OUT/'owned-transaction-verification.json').read_text('utf-8'));assert not any(owned['residual'].values())
    delayed=json.loads((OUT/'scheduled-cleanup-verification.json').read_text('utf-8'));assert delayed['remainingRemovedOwnedEntries']==0
    shutdown=json.loads((OUT/'shutdown-verification.json').read_text('utf-8-sig'));assert shutdown['confirmedRecordedServicesExited'] and not shutdown['remainingListening']
    observers=json.loads((OUT/'observer-shutdown.json').read_text('utf-8-sig'));assert not observers['remaining']
    absent=[]
    for line in (OUT/'order.stdout.log').read_text('utf-8',errors='replace').splitlines():
        if '关单任务对应的订单不存在' in line:
            when=stamp(line[:29]);absent.extend(row['tag'] for row in rows if row['startMs']<=when<=row['endMs'])
    assert not absent,'Deleted-order delayed callbacks contaminated formal windows'
    token=w.c.base.redis('HGET',w.m.TOKEN,'2');assert token is None or int(token)==810
    machines=[json.loads(line) for line in (OUT/'machine-resources.jsonl').read_text('utf-8-sig').splitlines()]
    formal=[sample for sample in machines if any(r['startMs']<=stamp(sample['utc'])<=r['endMs'] for r in rows)]
    heaps=[json.loads(line) for line in (OUT/'heap-resources.jsonl').read_text('utf-8').splitlines()]
    ratios={};pressure={};streak={}
    for sample in heaps:
        for item in sample['services']:
            name=item['service'];used=item['jvm.memory.used'].get('VALUE');maximum=item['jvm.memory.max'].get('VALUE')
            if used is None or not maximum:continue
            ratio=used/maximum;ratios[name]=max(ratios.get(name,0),ratio)
            streak[name]=streak.get(name,0)+1 if ratio>.8 else 0;pressure[name]=max(pressure.get(name,0),streak[name])
    required={'CarriageDirectoryTest','CarriageReservationTest','PurchaseMetadataServiceTest','PurchaseTicketOrchestrationTest',
        'PurchaseTicketServiceTest','SeatAllocatorTest','TicketAvailabilityTokenBucketTest','TicketCallbackServiceTest'}
    tests=[ET.parse(path).getroot() for path in (OUT/'tests-final').glob('TEST-*.xml')
           if path.stem.rsplit('.',1)[-1] in required]
    assert len(tests)==len(required)
    testCount=sum(int(x.attrib['tests']) for x in tests);assert testCount==49
    assert all(int(x.attrib['errors'])==0 and int(x.attrib['failures'])==0 for x in tests)
    oversell=[json.loads((OUT/f'oversell-{i}.json').read_text('utf-8')) for i in range(3)]
    assert all(r['success']==r['distinctSeats']==r['distinctOrders']==10 for r in oversell)
    result=dict(formalRounds=36,success=sum(r['success'] for r in rows),availableSeats=810,token=None if token is None else int(token),
        outsidePoolUnchanged=True,tests=testCount,testSuites=sorted(required),oversell=oversell,owned=owned,delayed=delayed,servicesStopped=True,
        allFormalDelayTasksRemovedImmediately=True,removedInFormal=sum(row['removedDelayTasks'] for row in cleanups),observersStopped=True,
        baselineSha256=sha(baseline),candidateSha256=sha(candidate),changedBaselineSource=changed,
        shutdownFreeMemoryGB=shutdown['freeMemoryGB'],machinePoints=len(formal),
        minFreeMemoryGB=min(x['freeMemoryGB'] for x in formal),maxSampledCpu=max(x['cpuPercent'] for x in formal),
        maxSampledPageReads=max(x['pageReadsPerSec'] for x in formal),heapMaxRatios=ratios,maxConsecutiveOver80=pressure,
        gc=gc_summary(rows),gcClockAlignment='OS process creation plus JVM uptime; approximate at subsecond boundaries',
        deletedOrderTimeoutsInFormal=0)
    w.dump(OUT/'warm-final-verification.json',result)
    print(json.dumps({key:result[key] for key in ['formalRounds','success','availableSeats','token','tests','outsidePoolUnchanged',
        'allFormalDelayTasksRemovedImmediately','deletedOrderTimeoutsInFormal','servicesStopped','observersStopped','shutdownFreeMemoryGB']},ensure_ascii=False))
if __name__=='__main__':main()
