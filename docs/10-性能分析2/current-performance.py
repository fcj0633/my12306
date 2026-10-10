"""Current-HEAD only, bounded-inventory JMeter experiment; never logs credentials.

Reuses reviewed local DB/Redis/login/cancellation primitives. Raw files and JWTs
are ignored by this directory's .gitignore. No business-source edits are needed.
"""
import argparse, concurrent.futures, csv, importlib.util, json, math, os
import pathlib, statistics, subprocess, threading, time, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OLD = ROOT / 'docs/5-后续开发规划/04-并发性能测试/scripts/stock-precheck-ab.py'
spec = importlib.util.spec_from_file_location('local_perf', OLD)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
OUT = HERE / 'results'
OUT.mkdir(exist_ok=True)
base.OUT = OUT
LEVELS = [1, 5, 10, 20, 50, 100, 200, 400]
EXTRA_PORTS = []
JMETER_SAMPLE_VARIABLES = []
_guard_last_health = 0
_redis_local = threading.local()

def pooled_redis(*args):
    """One RESP connection per tool thread; avoid Windows ephemeral-port exhaustion."""
    if not hasattr(_redis_local, 'conn'):
        _redis_local.conn = base.socket.create_connection(('192.168.204.128',6379),timeout=10)
        _redis_local.reader = _redis_local.conn.makefile('rb')
        pooled_redis('AUTH',base.REDIS_PASS)
    encoded=[str(p).encode('utf-8') for p in args]
    _redis_local.conn.sendall(b'*'+str(len(encoded)).encode()+b'\r\n'+b''.join(
        b'$'+str(len(p)).encode()+b'\r\n'+p+b'\r\n' for p in encoded))
    def read():
        line=_redis_local.reader.readline()
        if not line:raise RuntimeError('Redis tool connection closed')
        kind,data=line[:1],line[1:-2]
        if kind==b'-':raise RuntimeError('Redis tool command rejected')
        if kind==b'$':
            n=int(data)
            if n<0:return None
            value=_redis_local.reader.read(n);_redis_local.reader.read(2)
            return value.decode('utf-8')
        if kind==b'*':return [read() for _ in range(int(data))]
        return data.decode()
    return read()

base.redis=pooled_redis
NAMES = ['my12306.purchase.seat-lock.hold', 'my12306.purchase.passenger.remote',
 'my12306.purchase.order.remote', 'my12306.purchase.order.success',
 'my12306.purchase.order.ambiguous', 'my12306.purchase.compensation.fail',
 'my12306.token.pass', 'my12306.token.reject', 'my12306.token.load',
 'my12306.token.degrade', 'hikaricp.connections.active',
 'hikaricp.connections.idle', 'hikaricp.connections.pending',
 'hikaricp.connections.max', 'hikaricp.connections.acquire',
 'hikaricp.connections.timeout', 'process.cpu.time', 'process.cpu.usage',
 'system.cpu.usage', 'jvm.threads.live', 'jvm.memory.used',
 'tomcat.threads.current', 'tomcat.threads.busy', 'tomcat.threads.config.max']

def metric(port, name):
    try:
        data = base.api(port, '/actuator/metrics/' + name)
        return {r['statistic']: r['value'] for r in data['measurements']}
    except Exception:
        return {}

def metrics():
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as p:
        values = list(p.map(lambda n: metric(9002, n), NAMES))
    return dict(zip(NAMES, values))

def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def snapshot_db():
    result = dict(digest=base.digest(), status=base.sql("SHOW GLOBAL STATUS WHERE Variable_name IN "
        "('Innodb_row_lock_waits','Innodb_row_lock_time','Threads_connected');"))
    if 9003 in EXTRA_PORTS:
        rows=base.sql("SELECT DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_ROWS_EXAMINED "
                     "FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='12306_order';")
        result['orderDigest']={r[0]:dict(text=r[1],count=int(r[2]),time=int(r[3]),rows=int(r[4])) for r in rows}
    return result

def extra_metrics():
    names=['hikaricp.connections.active','hikaricp.connections.pending','hikaricp.connections.max',
           'hikaricp.connections.acquire','hikaricp.connections.timeout','hikaricp.connections.usage']
    return {str(port):{name:metric(port,name) for name in names} for port in EXTRA_PORTS}

def runtime_guard():
    """Abort only on owned service failure or persistent severe measured memory pressure."""
    global _guard_last_health
    for service in ['ticket','order','user','gateway','nacos']:
        for suffix in ['stdout','stderr']:
            log=OUT/(service+'.'+suffix+'.log')
            if log.exists() and 'OutOfMemoryError' in log.read_text('utf-8',errors='replace'):
                raise RuntimeError('Owned service OOM: '+service)
    if time.monotonic()-_guard_last_health>=5:
        _guard_last_health=time.monotonic()
        for port in [9000,9001,9002,9003]:
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health',timeout=2) as response:
                    if json.load(response).get('status')!='UP':raise RuntimeError('Not UP')
            except Exception as error:
                raise RuntimeError('Owned service unhealthy on port '+str(port)) from error
    path=OUT/'machine-resources.jsonl'
    if path.exists():
        lines=path.read_text('utf-8-sig').splitlines()[-3:]
        if len(lines)==3:
            values=[json.loads(line) for line in lines]
            if all(value['freeMemoryGB']<.25 and value['pageReadsPerSec']>0 for value in values):
                raise RuntimeError('Persistent low memory plus paging; stop measurement')

def redis_snapshot():
    return {section: base.redis('INFO', section) for section in ['commandstats','stats','errorstats','cpu']}

def pools(scenario):
    if scenario == 's1':
        return [('1', '2', '北京南', '宁波', '810')]
    # Seat rows alone do not prove buyability: train 3 has legacy type-1 rows,
    # but its BULLET vehicle only supports 3/4/5/13 and has no type-1 price.
    # Preserve the planned type-1/type-2 scope, selecting supported priced OD.
    return [tuple(r) for r in base.sql("SELECT s.train_id,s.seat_type,s.start_station,s.end_station,COUNT(*) "
        "FROM 12306_ticket.t_seat s JOIN 12306_ticket.t_train t ON t.id=s.train_id "
        "WHERE s.del_flag=0 AND s.seat_type IN (1,2) AND t.train_type=0 AND t.del_flag=0 "
        "AND EXISTS (SELECT 1 FROM 12306_ticket.t_train_station_price p WHERE p.del_flag=0 "
        "AND p.train_id=s.train_id AND p.seat_type=s.seat_type AND p.departure=s.start_station AND p.arrival=s.end_station) "
        "GROUP BY s.train_id,s.seat_type,s.start_station,s.end_station HAVING COUNT(*)>=100 "
        "ORDER BY s.train_id,s.seat_type,s.start_station,s.end_station;")]

def prepare(scenario):
    """Explicit user-authorized fixture reset, scoped by target pools/order IDs."""
    targets = pools(scenario)
    predicate = ' OR '.join("(train_id=%s AND seat_type=%s AND start_station='%s' AND end_station='%s')"
        % r[:4] for r in targets)
    # Include all tickets of each affected order, so multi-pool orders are not split.
    # One connection-local temporary table avoids hundreds of full-table scans
    # across the large historical test dataset. No persistent schema changes.
    base.sql("USE 12306_ticket; CREATE TEMPORARY TABLE perf_orders (order_sn VARCHAR(64) PRIMARY KEY) "
        "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; "
        f"INSERT IGNORE INTO perf_orders SELECT DISTINCT order_sn FROM 12306_ticket.t_ticket WHERE {predicate}; "
        "CREATE TEMPORARY TABLE perf_coords AS SELECT DISTINCT t.train_id,t.seat_type,t.start_station,"
        "t.end_station,t.carriage_number,t.seat_number FROM 12306_ticket.t_ticket t "
        "JOIN perf_orders o ON o.order_sn=t.order_sn; "
        "UPDATE 12306_ticket.t_seat s JOIN perf_coords t ON s.train_id=t.train_id "
        "AND s.seat_type=t.seat_type AND s.start_station=t.start_station AND s.end_station=t.end_station "
        "AND s.carriage_number=t.carriage_number AND s.seat_number=t.seat_number SET s.seat_status=0; "
        "DELETE p FROM 12306_pay.t_pay p JOIN perf_orders o ON o.order_sn=p.order_sn; "
        "DELETE i FROM 12306_order.t_order_item i JOIN perf_orders o ON o.order_sn=i.order_sn; "
        "DELETE d FROM 12306_order.t_order d JOIN perf_orders o ON o.order_sn=d.order_sn; "
        "DELETE t FROM 12306_ticket.t_ticket t JOIN perf_orders o ON o.order_sn=t.order_sn;")
    base.sql(f'UPDATE 12306_ticket.t_seat SET seat_status=0 WHERE del_flag=0 AND ({predicate});')
    for train, seat, dep, arr, _ in targets:
        base.redis('DEL', f'my12306-ticket-service:train_station_token_bucket:{train}_{dep}_{arr}',
          f'my12306-ticket-service:train_station_remaining_ticket:{train}_{dep}_{arr}')
    dump(OUT / f'{scenario}-targets.json', targets)
    print('Prepared pools', len(targets), 'distinct locks', len({r[:2] for r in targets}), flush=True)

def injector_plan():
    text = (base.PERF/'jmx/p2-throughput.jmx').read_text('utf-8-sig')
    begin = text.index("def client = vars.getObject('perf.http.client')")
    end = text.index("def lines = props.get('perf.user.lines')", begin)
    text = text[:begin] + """SampleResult.samplePause()
def client = props.get('current.client')
if (client == null) {
 synchronized (props) {
  client = props.get('current.client')
  if (client == null) {
   client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
   props.put('current.client', client)
   props.put('current.ready', new java.util.concurrent.CountDownLatch(Integer.parseInt(props.getProperty('threads'))))
   props.put('current.start', new java.util.concurrent.atomic.AtomicLong(0))
   props.put('current.count', new java.util.concurrent.atomic.AtomicInteger(0))
  }
 }
}
if (vars.get('current.joined') == null) {
 vars.put('current.joined', 'true')
 props.get('current.ready').countDown()
 if (!props.get('current.ready').await(60, java.util.concurrent.TimeUnit.SECONDS)) {
  throw new IllegalStateException('Injector start barrier timed out')
 }
}
def start = props.get('current.start')
start.compareAndSet(0L, System.nanoTime())
double budget = Double.parseDouble(props.getProperty('windowSec'))
if ((System.nanoTime() - start.get()) / 1e9 >= budget ||
    props.get('current.count').incrementAndGet() > Integer.parseInt(props.getProperty('requestCap'))) {
 SampleResult.setIgnore()
 ctx.getThread().stop()
 return
}
""" + text[end:]
    text = text.replace('<stringProp name="ThreadGroup.duration">${__P(duration,10)}</stringProp>',
        '<stringProp name="ThreadGroup.duration">120</stringProp>')
    # Timing covers the HTTP call, excluding injector initialization/start barrier.
    text = text.replace('def response = client.send(request,',
       'SampleResult.sampleResume()\nSampleResult.setTimeStamp(System.currentTimeMillis())\ndef response = client.send(request,')
    text = text.replace("if (msg.contains('已无余票'))", "if (msg.contains('已无余票') || msg.contains('令牌'))")
    plan = OUT/'current-throughput.jmx'
    plan.write_text(text, encoding='utf-8')
    return plan

def run(scenario, level, seconds, tag):
    if (OUT/f'{scenario}-{level}-{tag}.jtl').exists():
        tag += '-' + str(time.time_ns())
    targets = pools(scenario)
    keys = [f'my12306-ticket-service:train_station_token_bucket:{r[0]}_{r[2]}_{r[3]}' for r in targets]
    target_file = OUT/f'{scenario}-targets.csv'
    dispatch = targets
    if scenario == 's2':
        grouped = {}
        for r in targets: grouped.setdefault(r[:2], []).append(r)
        period = math.lcm(*(len(v) for v in grouped.values()))
        dispatch = [v[i % len(v)] for i in range(period) for v in grouped.values()]
    target_file.write_text('trainId,seatType,departure,arrival\n' + ''.join(
      f'{r[0]},{r[1]},{r[2].encode().hex()},{r[3].encode().hex()}\n' for r in dispatch), encoding='utf-8')
    before_inv = {int(r[0]): int(r[1]) for r in base.sql('SELECT id,seat_status FROM 12306_ticket.t_seat WHERE del_flag=0;')}
    if scenario == 's1':
        target_ids = set(base.inventory())
    else:
        predicate = ' OR '.join("(train_id=%s AND seat_type=%s AND start_station='%s' AND end_station='%s')" % r[:4] for r in targets)
        target_ids = {int(r[0]) for r in base.sql(f'SELECT id FROM 12306_ticket.t_seat WHERE del_flag=0 AND ({predicate});')}
    if scenario == 's1' and (len(target_ids) != 810 or any(before_inv[i] != 0 for i in target_ids)):
        raise RuntimeError('S1 must begin with the same 810 available seats')
    # Refresh TTL outside the measured window, not token values. Formal rounds
    # require a warm bucket that agrees with the current fixture.
    for r, key in zip(targets, keys):
        value = base.redis('HGET',key,r[1])
        if tag.startswith('run-') and value is None:
            raise RuntimeError('Formal round requires a warmed token bucket')
        if value is not None:
            actual = base.sql("SELECT COUNT(*) FROM 12306_ticket.t_seat WHERE del_flag=0 AND seat_status=0 "
              "AND train_id=%s AND seat_type=%s AND start_station='%s' AND end_station='%s';" % r[:4])[0][0]
            if int(value) != int(actual): raise RuntimeError('Token/DB inventory mismatch before round')
            base.redis('EXPIRE',key,'600')
    before_tickets = base.ticket_ids()
    before_metrics, before_db = metrics(), snapshot_db()
    before_extra=extra_metrics()
    before_redis = redis_snapshot()
    token_before = {k: base.redis('HGETALL', k) for k in keys}
    path = OUT/f'{scenario}-{level}-{tag}.jtl'
    cap = 730 if scenario == 's1' else 730
    args = [base.JAVA, '-Xms64m', '-Xmx256m', '-Xss512k', f'-Xlog:gc:file={path.with_suffix(".gc.log")}', r'-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix',
       '-jar', str(base.JMETER/'bin/ApacheJMeter.jar'), '-n', '-t', str(injector_plan()),
       '-l', str(path), '-j', str(path.with_suffix('.log')), f'-Jthreads={level}',
       '-Jramp=0', f'-JwindowSec={seconds}', f'-JrequestCap={cap}',
       f'-JuserFile={OUT / "users.csv"}', f'-JtargetsFile={target_file}',
       '-JbaseUrl=http://127.0.0.1:9000', '-Jjmeter.save.saveservice.response_message=true']
    if JMETER_SAMPLE_VARIABLES:args.append('-Jsample_variables='+','.join(JMETER_SAMPLE_VARIABLES))
    resource = []
    stop = threading.Event()
    def sample():
        while not stop.is_set():
            resource.append(dict(time=time.time(), values={n: metric(9002,n) for n in
              ['hikaricp.connections.active','hikaricp.connections.pending','process.cpu.usage',
               'system.cpu.usage','jvm.threads.live','tomcat.threads.busy']},extra=extra_metrics()))
            stop.wait(1)
    monitor = threading.Thread(target=sample, daemon=True)
    try:
        monitor.start()
        with path.with_suffix('.stdout.log').open('w') as log:
            p = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW)
            try:
                deadline=time.monotonic()+180
                while p.poll() is None:
                    runtime_guard()
                    if time.monotonic()>deadline:raise subprocess.TimeoutExpired(args,180)
                    time.sleep(.5)
            except subprocess.TimeoutExpired:
                p.kill();p.wait();raise
            except Exception:
                p.kill();p.wait();raise
        stop.set();monitor.join(timeout=10)
        if p.returncode: raise RuntimeError('JMeter failed; see raw log')
        after_metrics, after_db = metrics(), snapshot_db()
        after_extra=extra_metrics()
        data = list(csv.DictReader(path.open(encoding='utf-8-sig')))
        if not data: raise RuntimeError('No HTTP samples; inspect injector log')
        ok = [r for r in data if r['responseMessage']=='OK']
        start_ms = min(int(r['timeStamp']) for r in data)
        end_ms = max(int(r['timeStamp'])+int(r['elapsed']) for r in data)
        window = (end_ms-start_ms)/1000
        after_inv = {int(r[0]):int(r[1]) for r in base.sql('SELECT id,seat_status FROM 12306_ticket.t_seat WHERE del_flag=0;')}
        new = base.ticket_ids()-before_tickets
        accounting = {'tickets': len(new), 'changedSeats': sum(before_inv[k]!=after_inv[k] for k in target_ids)}
        if new:
            ids = ','.join(map(str,new))
            row = base.sql(f'SELECT COUNT(DISTINCT order_sn),COUNT(DISTINCT train_id,start_station,end_station,seat_type,carriage_number,seat_number) FROM 12306_ticket.t_ticket WHERE id IN ({ids});')[0]
            sns = ','.join("'"+r[0]+"'" for r in base.sql(f'SELECT DISTINCT order_sn FROM 12306_ticket.t_ticket WHERE id IN ({ids});'))
            accounting.update(distinctOrders=int(row[0]),distinctSeats=int(row[1]),
                orders=int(base.sql(f'SELECT COUNT(*) FROM 12306_order.t_order WHERE order_sn IN ({sns});')[0][0]),
                items=int(base.sql(f'SELECT COUNT(*) FROM 12306_order.t_order_item WHERE order_sn IN ({sns});')[0][0]))
        def delta(name, stat):
            return after_metrics[name].get(stat,0)-before_metrics[name].get(stat,0)
        count = delta('my12306.purchase.seat-lock.hold','COUNT')
        success_count = delta('my12306.purchase.order.success','COUNT')
        result = dict(scenario=scenario,concurrency=level,tag=tag,windowConfigured=seconds,
            requestCap=cap,capReached=len(data)>=cap,observedSeconds=window,samples=len(data),success=len(ok),
            participatingThreads=len({r['threadName'] for r in data}),
            httpStartMs=start_ms,httpEndMs=end_ms,
            totalQps=len(data)/window,successQps=len(ok)/window,successRate=len(ok)/len(data),
            quantiles={label: {str(q):base.percentile([int(r['elapsed']) for r in rows],q)
               for q in [.5,.95,.99]} for label,rows in [('all',data),('success',ok)]},
            holdCount=count,holdTotalSeconds=delta('my12306.purchase.seat-lock.hold','TOTAL_TIME'),
            holdAvgMs=delta('my12306.purchase.seat-lock.hold','TOTAL_TIME')*1000/count if count else None,
            tokenPass=delta('my12306.token.pass','COUNT'),tokenReject=delta('my12306.token.reject','COUNT'),
            tokenDegrade=delta('my12306.token.degrade','COUNT'),orderSuccess=success_count,
            classes={c:sum(r['responseMessage']==c for r in data) for c in set(r['responseMessage'] for r in data)},
            accounting=accounting,metricsBefore=before_metrics,metricsAfter=after_metrics,
            extraMetricsBefore=before_extra,extraMetricsAfter=after_extra,
            dbBefore=before_db,dbAfter=after_db,tokenBefore=token_before,
            redisBefore=before_redis,redisAfter=redis_snapshot(),
            lockKeys=len({r[:2] for r in targets}),odPools=len(targets),
            activeLockKeys=len({r[:2] for r in dispatch[:level]}),activeOdPools=len({r[:4] for r in dispatch[:level]}),
            tokenAfter={k:base.redis('HGETALL',k) for k in keys},resource=resource,
            poolBefore=sum(before_inv[k]==0 for k in target_ids),
            poolAfter=sum(after_inv[k]==0 for k in target_ids))
        result['valid'] = len(ok)==len(data) and all(n==len(ok) for n in accounting.values()) and success_count==len(ok) and result['tokenReject']==0 and result['tokenDegrade']==0
        dump(path.with_suffix('.json'),result)
        print(json.dumps({k:result[k] for k in ['scenario','concurrency','tag','samples','successQps','holdAvgMs','capReached','valid']},ensure_ascii=False),flush=True)
        return result
    finally:
        stop.set()
        base.cleanup(before_tickets)
        restored = {int(r[0]):int(r[1]) for r in base.sql('SELECT id,seat_status FROM 12306_ticket.t_seat WHERE del_flag=0;')}
        if any(restored[k] != before_inv[k] for k in target_ids): raise RuntimeError('Target inventory did not restore after cancellation')

def matrix(scenario, skip_initial_warm=False, resume=False):
    path=OUT/f'{scenario}-matrix.json'
    results = json.loads(path.read_text('utf-8')) if resume and path.exists() else []
    if any(not r['valid'] for r in results):raise RuntimeError('Cannot resume invalid matrix')
    base.refresh()
    if scenario == 's2':
        groups={}
        for r in pools(scenario):groups.setdefault(r[:2],[]).append(r)
        # Reach every OD through the real purchase/load path before low-N runs.
        seed_threads=math.lcm(*(len(v) for v in groups.values()))*len(groups)
        seed=run(scenario,seed_threads,5,'warm-all-pools')
        if not seed['valid']:raise RuntimeError('Multi-pool warmup invalid; diagnose before measuring')
    # Warmup traffic is cleaned after each short slice, outside formal windows.
    if not skip_initial_warm and not resume:
        for i in range(12): run(scenario,20,5,f'warm-initial-{i}')
    for level in LEVELS:
        completed=len([r for r in results if r['concurrency']==level])
        if completed>=3:continue
        base.refresh()  # Redis login TTL is 30 minutes; refresh before every level.
        for i in range(4): run(scenario,level,5,f'warm-{level}-{i}')
        calibration = run(scenario,level,2,f'calibration-{level}')
        seconds = min(8, max(.2,(650-level)/max(calibration['successQps'],1))) if scenario=='s1' else 5
        for rep in range(completed,3):
            results.append(run(scenario,level,seconds,f'run-{rep}'))
            dump(OUT/f'{scenario}-matrix.json',results)
            if not results[-1]['valid']: raise RuntimeError('Invalid formal round; diagnose before continuing')

def extend_variance(scenario):
    path=OUT/f'{scenario}-matrix.json'
    results=json.loads(path.read_text('utf-8'))
    for level in LEVELS:
        rows=[r for r in results if r['concurrency']==level]
        if len(rows)!=3 or max(r['successQps'] for r in rows)<=1.5*min(r['successQps'] for r in rows):continue
        base.refresh()
        for i in range(4):run(scenario,level,5,f'warm-variance-{level}-{i}')
        seconds=rows[0]['windowConfigured']
        for rep in [3,4]:
            r=run(scenario,level,seconds,f'run-extra-{rep}')
            if not r['valid']:raise RuntimeError('Invalid additional diagnostic round')
            results.append(r);dump(path,results)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['probe','prepare','refresh','run','matrix','extend'])
    p.add_argument('--scenario',choices=['s1','s2'],default='s1');p.add_argument('--level',type=int,default=1)
    p.add_argument('--seconds',type=float,default=2);p.add_argument('--tag',default='smoke')
    p.add_argument('--skip-initial-warm',action='store_true',help='Only when the same service JVMs already completed initial warmup')
    p.add_argument('--resume',action='store_true',help='Retain valid completed rounds and run only missing rounds')
    a=p.parse_args()
    if a.action=='probe': print('Redis',base.redis('PING'),'inventory',len(base.inventory()),'pools',pools('s2'))
    elif a.action=='prepare': prepare(a.scenario)
    elif a.action=='refresh': base.refresh()
    elif a.action=='run': run(a.scenario,a.level,a.seconds,a.tag)
    elif a.action=='extend': extend_variance(a.scenario)
    else: matrix(a.scenario,a.skip_initial_warm,a.resume)
