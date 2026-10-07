"""Isolated stock-precheck A/B experiment; credentials are read locally, never logged.

Uses existing JMeter plans. Never invokes the broad inventory-reset scripts.
Only tickets/order numbers created by this process are cancelled/deleted.
"""
import argparse, concurrent.futures, csv, json, math, os, pathlib, re, statistics
import socket, subprocess, time, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[4]
PERF = pathlib.Path(__file__).resolve().parents[1]
OUT = PERF / 'results' / 'stock-precheck'
OUT.mkdir(parents=True, exist_ok=True)
MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
JAVA = r'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
JMETER = pathlib.Path(r'D:\Program Files\jmeter')
POOL = "train_id=1 AND seat_type=2 AND start_station='北京南' AND end_station='宁波' AND del_flag=0"
script = (PERF/'scripts/run-concurrency-matrix.ps1').read_text('utf-8-sig')
DB_PASS = re.search(r'\[string\]\$MySqlPassword\s*=\s*"([^"]+)"', script).group(1)
script = (PERF/'scripts/refresh-tokens.ps1').read_text('utf-8-sig')
USER_PASS = re.search(r'\[string\]\$Password\s*=\s*"([^"]+)"', script).group(1)
config = (ROOT/'12306/my12306/services/ticket-services/src/main/resources/application.yml').read_text('utf-8')
REDIS_PASS = re.search(r'^\s+password:\s*([^\r\n]+)',config,re.M).group(1).strip().strip('"\'')

def sql(query):
    env = dict(os.environ, MYSQL_PWD=DB_PASS)
    p = subprocess.run([MYSQL,'-u','root','-h','127.0.0.1','--connect-timeout=5',
        '--default-character-set=utf8mb4','-N','-B','-e',query],env=env,capture_output=True)
    if p.returncode: raise RuntimeError('Database operation failed: '+p.stderr.decode('utf-8','replace'))
    return [line.split('\t') for line in p.stdout.decode('utf-8').splitlines()]

def api(port,path,body=None,token=None):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']=token
    req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,
        data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None,headers=headers)
    with urllib.request.urlopen(req,timeout=120) as response: return json.load(response)

def redis(*args):
    with socket.create_connection(('192.168.204.128',6379),timeout=5) as conn:
        f=conn.makefile('rb')
        def cmd(parts):
            encoded=[str(p).encode('utf-8') for p in parts]
            conn.sendall(b'*'+str(len(parts)).encode()+b'\r\n'+b''.join(b'$'+str(len(p)).encode()+b'\r\n'+p+b'\r\n' for p in encoded))
            def read():
                line=f.readline(); kind=line[:1]; data=line[1:-2]
                if kind==b'-': raise RuntimeError('Redis command rejected')
                if kind==b'$':
                    n=int(data)
                    if n<0:return None
                    value=f.read(n);f.read(2);return value.decode('utf-8')
                if kind==b'*':return [read() for _ in range(int(data))]
                return data.decode()
            return read()
        cmd(['AUTH',REDIS_PASS]); return cmd(args)

def clear_pool_cache():
    redis('DEL','my12306-ticket-service:train_station_token_bucket:1_北京南_宁波',
        'my12306-ticket-service:train_station_remaining_ticket:1_北京南_宁波')

def users():
    return list(csv.DictReader((OUT/'users.csv').open(encoding='utf-8-sig')))

def refresh():
    source=list(csv.DictReader((PERF/'results/users.csv').open(encoding='utf-8-sig')))
    def login(row):
        result=api(9000,'/api/user-service/v1/login',{'usernameOrMailOrPhone':row['username'],'password':USER_PASS})
        row['token']=result['data']['accessToken']; return row
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: rows=list(pool.map(login,source))
    with (OUT/'users.csv').open('w',encoding='utf-8',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=['username','token','passengerId']);writer.writeheader();writer.writerows(rows)
    print('Refreshed sessions:',len(rows),flush=True)

def inventory():
    return {int(r[0]):int(r[1]) for r in sql(f'SELECT id,seat_status FROM 12306_ticket.t_seat WHERE {POOL} ORDER BY id;')}

def ticket_ids():
    return {int(r[0]) for r in sql("SELECT id FROM 12306_ticket.t_ticket WHERE username LIKE 'perf400_%';")}

def cleanup(before):
    now=ticket_ids();new=now-before
    if not new:return 0
    ids=','.join(map(str,new))
    rows=sql(f'SELECT order_sn,train_id,start_station,end_station,seat_type,carriage_number,seat_number FROM 12306_ticket.t_ticket WHERE id IN ({ids});')
    by_order={}
    for sn,train,departure,arrival,seat_type,carriage,seat in rows:
        payload=by_order.setdefault(sn,dict(orderSn=sn,trainId=int(train),departure=departure,arrival=arrival,seats=[]))
        payload['seats'].append(dict(seatType=int(seat_type),carriageNumber=carriage,seatNumber=seat))
    for sn,payload in by_order.items():
        result=api(9002,'/api/ticket-service/ticket/cancel-callback',payload)
        if str(result.get('code'))!='0': raise RuntimeError('Cancellation callback rejected')
    sns=','.join("'"+sn+"'" for sn in by_order)
    sql(f"DELETE FROM 12306_order.t_order_item WHERE order_sn IN ({sns}) AND username LIKE 'perf400_%'; DELETE FROM 12306_order.t_order WHERE order_sn IN ({sns}) AND username LIKE 'perf400_%'; DELETE FROM 12306_ticket.t_ticket WHERE id IN ({ids}) AND username LIKE 'perf400_%';")
    return len(new)

def digest():
    rows=sql("SELECT DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_ROWS_EXAMINED FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='12306_ticket';")
    return {r[0]:dict(text=r[1],count=int(r[2]),time=int(r[3]),rows=int(r[4])) for r in rows}

def metrics():
    names=['my12306.purchase.seat-lock.hold','my12306.purchase.order.success',
        'my12306.token.reject','my12306.token.degrade','hikaricp.connections.pending']
    result={}
    for name in names:
        try: result[name]={s['statistic']:s['value'] for s in api(9002,'/actuator/metrics/'+name)['measurements']}
        except Exception: result[name]={}
    return result

def percentile(vals,p):
    return sorted(vals)[max(0,math.ceil(len(vals)*p)-1)] if vals else None

def run(variant,threads,seconds,tag,hot=False):
    snapshot=inventory(); tickets=ticket_ids(); before_db=digest(); before_metrics=metrics()
    token_key='my12306-ticket-service:train_station_token_bucket:1_北京南_宁波'
    token_before=redis('HGET',token_key,'2')
    path=OUT/f'{variant}-{threads}-{tag}.jtl'
    target=OUT/'targets.csv'
    target.write_text('trainId,seatType,departure,arrival\n1,2,E58C97E4BAACE58D97,E5AE81E6B3A2\n',encoding='utf-8')
    source_plan=PERF/'jmx'/('p2-hotspot.jmx' if hot else 'p2-throughput.jmx')
    plan=OUT/('shared-hotspot.jmx' if hot else 'shared-throughput.jmx')
    text=source_plan.read_text('utf-8-sig')
    # HttpClient is thread-safe. One client per injector avoids 400 selector threads
    # and connection pools becoming the bottleneck; identical for A and B.
    old="""def client = vars.getObject('perf.http.client')
if (client == null) {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    vars.putObject('perf.http.client', client)
}"""
    new="""def client = props.get('precheck.shared.client')
if (client == null) {
    synchronized (props) {
        client = props.get('precheck.shared.client')
        if (client == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
            props.put('precheck.shared.client', client)
        }
    }
}"""
    if old not in text: raise RuntimeError('Unexpected JMeter client initialization')
    plan.write_text(text.replace(old,new),encoding='utf-8')
    args=[JAVA,'-Xms128m','-Xmx512m',r'-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix',
        '-jar',str(JMETER/'bin/ApacheJMeter.jar'),'-n','-t',str(plan),'-l',str(path),'-j',str(path.with_suffix('.log')),
        f'-Jthreads={threads}',f'-Jduration={seconds}','-Jramp=1',f'-JuserFile={OUT / "users.csv"}',
        f'-JtargetsFile={target}','-JbaseUrl=http://127.0.0.1:9000','-Jjmeter.save.saveservice.response_message=true']
    start=time.time()
    try:
        with path.with_suffix('.out.log').open('w') as log:
            process=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
            try: process.wait(timeout=180)
            except subprocess.TimeoutExpired: process.kill();process.wait();raise
        if process.returncode:raise RuntimeError('JMeter failed')
        after_metrics=metrics(); after_db=digest(); after_inv=inventory()
        data=list(csv.DictReader(path.open(encoding='utf-8-sig')))
        ok=[r for r in data if r['responseMessage']=='OK']
        window=(max(int(r['timeStamp'])+int(r['elapsed']) for r in data)-min(int(r['timeStamp']) for r in data))/1000
        lat=[int(r['elapsed']) for r in ok]
        delta=[]
        for key,a in after_db.items():
            b=before_db.get(key,dict(count=0,time=0,rows=0));count=a['count']-b['count']
            if count:delta.append(dict(text=a['text'],calls=count,totalMs=(a['time']-b['time'])/1e9,examined=a['rows']-b['rows']))
        hold_a=after_metrics['my12306.purchase.seat-lock.hold'];hold_b=before_metrics['my12306.purchase.seat-lock.hold']
        count=hold_a.get('COUNT',0)-hold_b.get('COUNT',0)
        hold=(hold_a.get('TOTAL_TIME',0)-hold_b.get('TOTAL_TIME',0))*1000/count if count else None
        classes={c:sum(r['responseMessage']==c for r in data) for c in set(r['responseMessage'] for r in data)}
        changed=[i for i in snapshot if snapshot[i]!=after_inv[i]]
        new_tickets=ticket_ids()-tickets
        order_count=item_count=distinct_seats=0
        if new_tickets:
            ids=','.join(map(str,new_tickets))
            distinct_seats=int(sql(f'SELECT COUNT(DISTINCT train_id,start_station,end_station,seat_type,carriage_number,seat_number) FROM 12306_ticket.t_ticket WHERE id IN ({ids});')[0][0])
            sns=','.join("'"+r[0]+"'" for r in sql(f'SELECT DISTINCT order_sn FROM 12306_ticket.t_ticket WHERE id IN ({ids});'))
            order_count=int(sql(f'SELECT COUNT(*) FROM 12306_order.t_order WHERE order_sn IN ({sns});')[0][0])
            item_count=int(sql(f'SELECT COUNT(*) FROM 12306_order.t_order_item WHERE order_sn IN ({sns});')[0][0])
        result=dict(variant=variant,threads=threads,seconds=seconds,tag=tag,samples=len(data),success=len(ok),classes=classes,
            durationSec=window,totalQps=len(data)/window,successQps=len(ok)/window,successP95=percentile(lat,.95),
            successP99=percentile(lat,.99),lockHoldMs=hold,availableBefore=sum(v==0 for v in snapshot.values()),
            availableAfter=sum(v==0 for v in after_inv.values()),changedSeats=len(changed),seatAccounted=len(changed)==len(ok),
            metricsBefore=before_metrics,metricsAfter=after_metrics,db=delta,jmeterWallSec=time.time()-start)
        # A 400-thread burst may use more than half of the existing 467 seats.
        # Preserve it as a burst observation, never label it a calibrated steady test.
        result['withinHalfInventoryBudget']=len(changed)<=result['availableBefore']/2
        result['accounting']=dict(tickets=len(new_tickets),orders=order_count,items=item_count,distinctSeats=distinct_seats)
        result['accountingPassed']=all(n==len(ok) for n in [len(new_tickets),order_count,item_count,distinct_seats])
        result['valid']=result['seatAccounted'] and result['accountingPassed'] and (hot or (result['availableAfter']>0 and len(ok)==len(data)))
        path.with_suffix('.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({k:result[k] for k in ['variant','threads','tag','success','samples','successQps','successP99','lockHoldMs','valid']},ensure_ascii=False),flush=True)
        return result
    finally:
        cleaned=cleanup(tickets)
        if inventory()!=snapshot:raise RuntimeError('Inventory restoration mismatch')
        token_after=redis('HGET',token_key,'2')
        expected_token=int(token_before) if token_before is not None else sum(v==0 for v in snapshot.values())
        if token_after is not None and int(token_after)!=expected_token:
            raise RuntimeError('Token restoration mismatch')
        print('Restored seats; new test tickets removed:',cleaned,flush=True)

def warm(variant):
    for i in range(3):run(variant,20,2,f'warm{i}')
    # Keep the warmed token bucket: cancellation returns exactly the released tokens.

def hot(variant):
    snapshot=inventory();available=[i for i,v in snapshot.items() if v==0]
    mask=available[10:]
    try:
        sql('UPDATE 12306_ticket.t_seat SET seat_status=2 WHERE id IN ('+','.join(map(str,mask))+') AND seat_status=0;')
        clear_pool_cache()
        return [run(variant,50,1,f'hot{i}',True) for i in range(3)]
    finally:
        sql('UPDATE 12306_ticket.t_seat SET seat_status=0 WHERE id IN ('+','.join(map(str,mask))+') AND seat_status=2;')
        clear_pool_cache()
        if inventory()!=snapshot:raise RuntimeError('Hot fixture restoration mismatch')

def summarize():
    matrices={v:json.loads((OUT/f'{v}-matrix.json').read_text('utf-8')) for v in ['A','B']}
    summary={}
    for variant,records in matrices.items():
        if len(records)!=15 or not all(r['valid'] for r in records):
            raise RuntimeError('Incomplete or invalid matrix: '+variant)
        levels={}
        for level in [20,50,100,200,400]:
            rows=[r for r in records if r['threads']==level]
            levels[level]={name:dict(median=statistics.median(r[name] for r in rows),
                minimum=min(r[name] for r in rows),maximum=max(r[name] for r in rows))
                for name in ['successQps','successP95','successP99','lockHoldMs','durationSec','success']}
        precheck=[d for r in records for d in r['db'] if d['text'].startswith('SELECT COUNT ( * ) AS `total` FROM `t_seat`') and '`seat_type`' in d['text']]
        summary[variant]=dict(levels=levels,samples=sum(r['samples'] for r in records),
            success=sum(r['success'] for r in records),precheckCalls=sum(d['calls'] for d in precheck),
            precheckMs=sum(d['totalMs'] for d in precheck))
    (OUT/'AB-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['refresh','probe','warm','matrix','hot','summarize'])
    parser.add_argument('--variant',default='A');parser.add_argument('--seconds',type=int,default=2)
    args=parser.parse_args()
    if args.action=='refresh':refresh()
    elif args.action=='probe':
        print('Redis:',redis('PING')); print('Inventory:',{s:list(inventory().values()).count(s) for s in [0,1,2]})
    elif args.action=='warm':warm(args.variant)
    elif args.action=='hot':hot(args.variant)
    elif args.action=='summarize':summarize()
    else:
        results=[]
        for level in [20,50,100,200,400]:
            for rep in range(3):results.append(run(args.variant,level,args.seconds,f'run{rep}'))
        (OUT/f'{args.variant}-matrix.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
