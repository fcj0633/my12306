"""Read-only final checks; run after both current matrices and cleanup finish."""
import datetime, hashlib, importlib.util, json, pathlib, re

HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('current',HERE/'current-performance.py')
current=importlib.util.module_from_spec(spec);spec.loader.exec_module(current)
base=current.base;OUT=current.OUT
rows=[]
for scenario in ['s1','s2']:
    rs=json.loads((OUT/f'{scenario}-matrix.json').read_text('utf-8'))
    assert len(rs)>=24 and all(r['valid'] for r in rs)
    assert all(r['samples']==r['success'] and r['tokenReject']==0 and r['tokenDegrade']==0 for r in rs)
    if scenario=='s1':assert all(r['poolBefore']==810 for r in rs)
    rows+=rs
targets=current.pools('s2')
predicate=' OR '.join("(train_id=%s AND seat_type=%s AND start_station='%s' AND end_station='%s')" % r[:4] for r in targets)
seat=base.sql(f'SELECT COUNT(*),SUM(seat_status=0),SUM(seat_status<>0) FROM 12306_ticket.t_seat WHERE del_flag=0 AND ({predicate});')[0]
assert int(seat[0])==12350 and int(seat[1])==12350 and int(seat[2])==0
target=current.pools('s1')[0]
pool=base.sql("SELECT COUNT(*),SUM(seat_status=0),SUM(seat_status<>0) FROM 12306_ticket.t_seat WHERE del_flag=0 "
    "AND train_id=%s AND seat_type=%s AND start_station='%s' AND end_station='%s';" % target[:4])[0]
assert list(map(int,pool))==[810,810,0]
tickets=base.sql(f"SELECT COUNT(*),SUM(username LIKE 'perf400_%'),SUM(create_time>='2026-10-08'),"
    f"SUM(order_sn IS NULL) FROM 12306_ticket.t_ticket WHERE {predicate};")[0]
tickets=[int(v) if v!='NULL' else 0 for v in tickets]
assert tickets[1]==0 and tickets[2]==0
# Historical callback fixtures have NULL order_sn and predate this run; they are
# not linked orders and are deliberately preserved rather than broadly deleted.
assert tickets[0]==tickets[3]
order_left=int(base.sql("SELECT COUNT(*) FROM 12306_order.t_order WHERE username LIKE 'perf400_%' "
    "AND create_time>='2026-10-08 20:28:43';")[0][0])
item_left=int(base.sql("SELECT COUNT(*) FROM 12306_order.t_order_item WHERE username LIKE 'perf400_%' "
    "AND create_time>='2026-10-08 20:28:43';")[0][0])
assert order_left==0 and item_left==0
tokens=[]
for r in targets:
    value=base.redis('HGET',f'my12306-ticket-service:train_station_token_bucket:{r[0]}_{r[2]}_{r[3]}',r[1])
    if value is not None:assert int(value)==int(r[4])
    tokens.append(dict(pool=list(r[:4]),dbAvailable=int(r[4]),token=None if value is None else int(value)))
environment=json.loads((OUT/'environment.json').read_text('utf-8'))
hashes={}
for service,expected in environment['artifactSha256'].items():
    jar=current.ROOT/f'12306/my12306/services/{service}-services/target/{service}-services-0.0.1-SNAPSHOT.jar'
    hashes[service]=hashlib.sha256(jar.read_bytes()).hexdigest()
    assert hashes[service]==expected
starts=json.loads((OUT/'process-start-times.json').read_text('utf-8-sig'))
actual={p['service']:p['ProcessId'] for p in starts}
starts={p['ProcessId']:datetime.datetime.fromisoformat(p['CreationDate']).timestamp() for p in starts}
gc={}
for service,pid in actual.items():
    log=(OUT/f'{service}.gc.log').read_text('utf-8',errors='replace')
    pauses=[]
    for line in log.splitlines():
        match=re.match(r'\[(\d+(?:\.\d+)?)s\].*Pause.* (\d+(?:\.\d+)?)ms$',line)
        if not match:continue
        when=starts[pid]+float(match[1])
        if any(r['httpStartMs']/1000<=when<=r['httpEndMs']/1000 for r in rows):
            pauses.append(dict(durationMs=float(match[2]),full='Pause Full' in line))
    gc[service]=dict(formalPauseCount=len(pauses),formalFullCount=sum(p['full'] for p in pauses),
        formalMaxPauseMs=max((p['durationMs'] for p in pauses),default=0),
        completeLogFullCount=log.count('Pause Full'),oom='OutOfMemoryError' in log)
result=dict(checkedAt=datetime.datetime.now().astimezone().isoformat(),
    formalRounds=len(rows),successfulPurchases=sum(r['success'] for r in rows),
    s1SeatCounts=list(map(int,pool)),s2SeatCounts=list(map(int,seat)),
    historicalNullOrderTicketRows=tickets[0],remainingPerf400Tickets=tickets[1],
    remainingTodayScopedTickets=tickets[2],remainingRunOrders=order_left,remainingRunItems=item_left,
    tokenPools=tokens,artifactSha256=hashes,gc=gc)
current.dump(OUT/'final-verification.json',result)
print(json.dumps({k:v for k,v in result.items() if k not in ['tokenPools','artifactSha256']},ensure_ascii=False))
