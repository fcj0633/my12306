"""Read-only inventory/artifact/GC verification for this diagnostic session."""
import datetime,hashlib,importlib.util,json,pathlib,re
HERE=pathlib.Path(__file__).resolve().parent;OUT=HERE/'results/stages'
s=importlib.util.spec_from_file_location('current',HERE/'current-performance.py');c=importlib.util.module_from_spec(s);s.loader.exec_module(c)
records=json.loads((OUT/'stage-matrix.json').read_text('utf-8'))+json.loads((OUT/'stage-confirm-matrix.json').read_text('utf-8'))
assert len(records)==18 and all(r['valid'] and r['poolBefore']==810 and not r['capReached'] for r in records)
assert all(r['samples']==r['success'] and r['tokenReject']==r['tokenDegrade']==0 for r in records)
pool=c.base.sql("SELECT COUNT(*),SUM(seat_status=0),SUM(seat_status<>0) FROM 12306_ticket.t_seat WHERE del_flag=0 "
    "AND train_id=1 AND seat_type=2 AND start_station='北京南' AND end_station='宁波';")[0]
assert list(map(int,pool))==[810,810,0]
residual={}
for schema,table in [('12306_ticket','t_ticket'),('12306_order','t_order'),('12306_order','t_order_item')]:
    residual[table]=int(c.base.sql(f"SELECT COUNT(*) FROM {schema}.{table} WHERE username LIKE 'perf400_%' AND create_time>='2026-10-08 23:00:00';")[0][0])
assert all(v==0 for v in residual.values())
token=c.base.redis('HGET','my12306-ticket-service:train_station_token_bucket:1_北京南_宁波','2')
assert token is None or int(token)==810
env=json.loads((HERE/'results/environment.json').read_text('utf-8'))
for service,expected in env['artifactSha256'].items():
    jar=c.ROOT/f'12306/my12306/services/{service}-services/target/{service}-services-0.0.1-SNAPSHOT.jar'
    assert hashlib.sha256(jar.read_bytes()).hexdigest()==expected
starts=json.loads((OUT/'process-start-times.json').read_text('utf-8-sig'));gc={}
for process in starts:
    log=(OUT/f"{process['service']}.gc.log").read_text('utf-8',errors='replace')
    start=datetime.datetime.fromisoformat(process['CreationDate']).timestamp();pauses=[]
    for line in log.splitlines():
        match=re.match(r'\[(\d+(?:\.\d+)?)s\].*Pause.* (\d+(?:\.\d+)?)ms$',line)
        if not match:continue
        when=start+float(match[1])
        if any(r['httpStartMs']/1000<=when<=r['httpEndMs']/1000 for r in records):pauses.append((float(match[2]),'Pause Full' in line))
    gc[process['service']]=dict(pauseCount=len(pauses),fullCount=sum(p[1] for p in pauses),maxPauseMs=max((p[0] for p in pauses),default=0),totalPauseMs=sum(p[0] for p in pauses))
result=dict(formalRounds=len(records),successfulPurchases=sum(r['success'] for r in records),seatCounts=list(map(int,pool)),
    transactionResidual=residual,token=None if token is None else int(token),artifactsUnchanged=True,gc=gc)
(OUT/'final-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))
