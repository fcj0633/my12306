"""Current carriage-lock implementation only. Raw results/JWTs stay in ignored results/carriage.

Never calls prepare(): existing non-test reservations must not be deleted/reset.
"""
import argparse, base64, concurrent.futures, csv, hashlib, importlib.util, json, math, os, pathlib, re, subprocess, threading, time

HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('current',HERE/'current-performance.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
OUT=HERE/'results/carriage';OUT.mkdir(exist_ok=True);c.OUT=OUT;c.base.OUT=OUT
c.NAMES += ['my12306.purchase.carriage.'+name for name in ['attempt','fast-success','candidate-miss','fallback','db-retry']]
TOKEN='my12306-ticket-service:train_station_token_bucket:1_北京南_宁波'
LAST_DISTRIBUTION={}
original_cleanup=c.base.cleanup

def cleanup(before):
    global LAST_DISTRIBUTION
    ids=c.base.ticket_ids()-before
    LAST_DISTRIBUTION={}
    if ids:
        joined=','.join(map(str,ids))
        LAST_DISTRIBUTION={row[0]:int(row[1]) for row in c.base.sql(
            f'SELECT carriage_number,COUNT(*) FROM 12306_ticket.t_ticket WHERE id IN ({joined}) GROUP BY carriage_number;')}
    return original_cleanup(before)
c.base.cleanup=cleanup

def read_csv(name):
    path=OUT/name
    return list(csv.DictReader(path.open(encoding='utf-8'))) if path.exists() else []

def inventory_clean():
    inventory=c.base.inventory()
    assert len(inventory)==810 and set(inventory.values())=={0},'Requires original 810 available seats; do not reset occupied business inventory'

def run(enabled,tag,seconds,level=20):
    inventory_clean()
    before=max((int(row['id']) for row in read_csv('traces.csv')),default=0)
    flag=OUT/'capture.flag'
    if enabled:flag.touch()
    elif flag.exists():flag.unlink()
    time.sleep(.3)
    try:result=c.run('s1',level,seconds,tag)
    finally:
        if flag.exists():flag.unlink()
    time.sleep(.4)
    traces=[row for row in read_csv('traces.csv') if int(row['id'])>before]
    locks=[row for row in read_csv('locks.csv') if int(row['id'])>before]
    trace_ids={row['id'] for row in traces}
    if enabled:
        assert len(trace_ids)==result['success'] and all(row['failed']=='false' for row in traces)
        for trace_id in trace_ids:
            names={row['stage'] for row in traces if row['id']==trace_id}
            assert {'purchase.total','seat.lock-wait','seat.unlock-safe','tx.body','tx.proxy','jdbc.commit','jdbc.seat-select'} <= names
    else:assert not traces
    result.update(probeEnabled=enabled,traceCount=len(trace_ids),carriageDistribution=LAST_DISTRIBUTION.copy(),
                  resourceLockKeys=9,legacyLockCountMeaning='legacy seat-type count; resourceLockKeys supersedes it')
    result['carriageCounters']={name:result['metricsAfter'][name].get('COUNT',0)-result['metricsBefore'][name].get('COUNT',0)
                               for name in c.NAMES if '.carriage.' in name}
    c.dump(OUT/f"{result['tag']}-result.json",result)
    for name,rows in [('traces',traces),('locks',locks)]:
        with (OUT/f"{result['tag']}-{name}.csv").open('w',encoding='utf-8',newline='') as file:
            if rows:
                writer=csv.DictWriter(file,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    return result

def payload(user):
    return dict(trainId='1',departure='北京南',arrival='宁波',passengers=[dict(passengerId=user['passengerId'],seatType=2)])

def burst(ports,tag,limited=False):
    inventory_clean();before=c.base.ticket_ids();snapshot=c.base.inventory()
    selected=[]
    if limited:
        # Ten original coordinates spread over nine carriages. Temporary fixture only,
        # recorded and restored in finally; never modifies another pool.
        rows=c.base.sql('SELECT id,carriage_number FROM 12306_ticket.t_seat WHERE '+c.base.POOL+' ORDER BY carriage_number,seat_number;')
        seen=set()
        for seat_id,carriage in rows:
            if carriage not in seen:selected.append(int(seat_id));seen.add(carriage)
        selected.append(next(int(row[0]) for row in rows if int(row[0]) not in selected))
        ids=','.join(map(str,selected))
        c.base.sql('UPDATE 12306_ticket.t_seat SET seat_status=2 WHERE '+c.base.POOL+f' AND id NOT IN ({ids});')
        c.base.clear_pool_cache()
    results=[];start=time.time()
    try:
        users=c.base.users()[:50];barrier=threading.Barrier(50)
        def buy(item):
            i,user=item;barrier.wait(timeout=30)
            try:return c.base.api(ports[i%len(ports)],'/api/ticket-service/ticket/purchase',payload(user),user['token'])
            except Exception as error:return dict(code='transport-error',message=type(error).__name__)
        with concurrent.futures.ThreadPoolExecutor(max_workers=50) as pool:results=list(pool.map(buy,enumerate(users)))
        success=sum(str(row.get('code'))=='0' for row in results)
        new=c.base.ticket_ids()-before
        inv=c.base.inventory()
        assert success==(10 if limited else 50),f'Unexpected successful count {success}'
        assert len(new)==success
        ids=','.join(map(str,new))
        coordinates=c.base.sql(f'SELECT COUNT(DISTINCT train_id,start_station,end_station,seat_type,carriage_number,seat_number),COUNT(DISTINCT order_sn) FROM 12306_ticket.t_ticket WHERE id IN ({ids});')[0]
        assert list(map(int,coordinates))==[success,success]
        assert sum(value==1 for value in inv.values())==success
        result=dict(tag=tag,ports=ports,requests=50,success=success,availableStart=10 if limited else 810,
                    availableEnd=sum(value==0 for value in inv.values()),distinctSeats=int(coordinates[0]),
                    distinctOrders=int(coordinates[1]),elapsedSeconds=time.time()-start,
                    classes={str(row.get('message','OK')):sum(row.get('message')==value.get('message') for value in results) for row in results})
        c.dump(OUT/f'{tag}.json',result);print(json.dumps(result,ensure_ascii=False),flush=True)
    finally:
        cleanup(before)
        if limited:
            c.base.sql('UPDATE 12306_ticket.t_seat SET seat_status=0 WHERE '+c.base.POOL+' AND seat_status=2;')
            c.base.clear_pool_cache()
        assert c.base.inventory()==snapshot,'Fixture failed to restore'

def preflight():
    inventory_clean()
    result=dict(mysql=c.base.sql('SELECT VERSION();')[0][0],redis=c.base.redis('PING'),inventory=810,
        outsidePool=c.base.sql('SELECT id,seat_status FROM 12306_ticket.t_seat WHERE NOT ('+c.base.POOL+') ORDER BY id;'),
        initialPerfTicketIds=sorted(c.base.ticket_ids()),
        artifactSha256={service:hashlib.sha256((c.ROOT/f'12306/my12306/services/{service}-services/target/{service}-services-0.0.1-SNAPSHOT.jar').read_bytes()).hexdigest() for service in ['user','order','ticket','gateway']})
    c.dump(OUT/'environment.json',result);print('Preflight OK: MySQL, Redis, original 810 available seats; no broad reset',flush=True)

def guard_check():
    inventory_clean();before=c.base.ticket_ids()
    cp=(c.ROOT/'12306/my12306/services/ticket-services/target/bench-classpath.txt').read_text('utf-8').strip()
    classes=c.ROOT/'12306/my12306/services/ticket-services/target/carriage-guard-classes';classes.mkdir(exist_ok=True)
    javac=pathlib.Path(c.base.JAVA).with_name('javac.exe')
    subprocess.run([str(javac),'-encoding','UTF-8','-cp',cp,'-d',str(classes),str(HERE/'diagnostics/CarriageGuard.java')],check=True,capture_output=True)
    for name in ['guard-ready','guard-release']:
        path=OUT/name
        if path.exists():path.unlink()
    argsfile=OUT/'guard.args'
    argsfile.write_text('-Xms32m\n-Xmx128m\n-Djdk.net.unixdomain.tmpdir=Z:\\disable-af-unix\n-cp\n"'+(str(classes)+';'+cp).replace('\\','/')+'"\nCarriageGuard\n'+base64.b64encode(str(OUT).encode()).decode()+'\n',encoding='ascii')
    env=dict(os.environ,BENCH_REDIS_PASSWORD=c.base.REDIS_PASS)
    with (OUT/'guard.log').open('w') as logfile:
        process=subprocess.Popen([c.base.JAVA,'@'+str(argsfile)],env=env,stdout=logfile,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            deadline=time.time()+30
            while not (OUT/'guard-ready').exists():
                if process.poll() is not None or time.time()>deadline:raise RuntimeError('External lock guard failed')
                time.sleep(.1)
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                users=c.base.users()[:2]
                futures=[pool.submit(c.base.api,port,'/api/ticket-service/ticket/purchase',payload(user),user['token']) for port,user in zip([9002,9012],users)]
                try:
                    time.sleep(2)
                    blocked=all(not future.done() for future in futures)
                    no_commit=c.base.ticket_ids()==before
                    assert blocked and no_commit,'Ticket did not respect the shared carriage keys'
                finally:(OUT/'guard-release').touch()
                results=[future.result(timeout=30) for future in futures]
                assert all(str(row.get('code'))=='0' for row in results)
            process.wait(timeout=20);assert process.returncode==0
            c.dump(OUT/'two-instance-guard.json',dict(blockedWhileGuardHeld=True,noReservationWhileHeld=True,successAfterRelease=2,ports=[9002,9012],keys=9))
            print('Two-instance resource-key guard passed; both purchases blocked then succeeded after unlock',flush=True)
        finally:
            (OUT/'guard-release').touch()
            if process.poll() is None:
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:process.kill();process.wait()
            cleanup(before)
            inventory_clean()

def performance():
    c.base.refresh()
    for i in range(3):
        result=run(False,f'carriage-warm-{i}',2)
        assert result['valid']
    calibration=run(False,'carriage-calibration',2)
    assert calibration['valid']
    seconds=min(8,max(1,math.floor(405/max(1,calibration['successQps']*1.5))))
    excluded=[]
    while True:
        results=[];shorten=False
        for i in range(3):
            for enabled in [False,True]:
                result=run(enabled,f'carriage-formal-{seconds}s-{i}-{int(enabled)}',seconds)
                if result['success']>405 or result['tokenReject'] or result['poolAfter']==0:
                    excluded.extend(results+[result]);shorten=True;break
                assert result['valid'] and not result['capReached']
                results.append(result);c.dump(OUT/'formal-in-progress.json',results)
            if shorten:break
        if not shorten:
            c.dump(OUT/'formal-matrix.json',results);c.dump(OUT/'excluded-rounds.json',excluded);break
        c.dump(OUT/'excluded-rounds.json',excluded)
        if seconds<=1:raise RuntimeError('One-second window still exceeds half inventory; report limitation')
        seconds-=1

def larger_matrix():
    calibrations=[]
    for level in [50,100]:
        c.base.refresh()
        for index in range(3):
            row=run(False,f'larger-warm-{level}-{index}',1,level)
            assert row['valid'] and not row['capReached']
        row=run(False,f'larger-calibration-{level}-1s',1,level)
        if row['success']>405 or row['capReached']:
            row=run(False,f'larger-calibration-{level}-0.5s',.5,level)
        assert row['valid'] and not row['capReached'] and row['success']<=405
        calibrations.append(row)
    c.dump(OUT/'calibrations.json',calibrations)
    rate=max(row['successQps'] for row in calibrations)
    seconds=min(2,max(.5,math.floor((405-100)/(max(rate,1)*1.5)*2)/2))
    excluded=[];sequence=0
    while True:
        results=[];shorten=False
        for level in [50,100]:
            c.base.refresh()
            for index in range(3):
                for enabled in [False,True]:
                    row=run(enabled,f'larger-formal-{sequence}-{level}-{seconds}s-{index}-{int(enabled)}',seconds,level)
                    if row['success']>405 or row['poolAfter']==0 or row['capReached']:
                        excluded.extend(results+[row]);shorten=True;break
                    assert row['valid'], 'Invalid round; investigate errors, do not classify as inventory-window exclusion'
                    results.append(row);c.dump(OUT/'formal-in-progress.json',results)
                if shorten:break
            if shorten:break
        if not shorten:
            c.dump(OUT/'formal-matrix.json',results);c.dump(OUT/'excluded-rounds.json',excluded);return
        c.dump(OUT/'excluded-rounds.json',excluded)
        if seconds<=.5:raise RuntimeError('Half-second window still exceeds inventory budget; report limit')
        seconds-=.5;sequence+=1

def verify_transactions():
    owned=set()
    for name in sorted(set(['ticket.stdout.log','ticket.initial.stdout.log','ticket2.stdout.log']+[path.name for path in OUT.glob('ticket.*.stdout.log')])):
        path=OUT/name
        if not path.exists():continue
        for line in path.read_text('utf-8',errors='replace').splitlines():
            if '购票锁已获取' in line and 'purchase_tickets_user_perf400_' in line:
                match=re.search(r'orderSn=(\d+)',line)
                if match:owned.add(match[1])
    residual={'t_order':0,'t_order_item':0,'t_ticket':0};ordered=sorted(owned)
    for index in range(0,len(ordered),200):
        values=','.join("'"+sn+"'" for sn in ordered[index:index+200])
        for schema,table in [('12306_order','t_order'),('12306_order','t_order_item'),('12306_ticket','t_ticket')]:
            residual[table]+=int(c.base.sql(f'SELECT COUNT(*) FROM {schema}.{table} WHERE order_sn IN ({values});')[0][0])
    assert not any(residual.values())
    fixtures=int(c.base.sql("SELECT COUNT(*) FROM 12306_ticket.t_seat WHERE carriage_number LIKE 'TEST-C%';")[0][0])
    assert fixtures==0
    inventory_clean()
    c.dump(OUT/'owned-transaction-verification.json',dict(loggedOrderCount=len(owned),residual=residual,isolatedFixturesRemaining=fixtures))
    print('Owned transaction verification passed:',len(owned),'orders; all residual counts zero',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['preflight','smoke','oversell','multi','performance','larger','verify'])
    parser.add_argument('--output-dir');parser.add_argument('--level',type=int,default=20);parser.add_argument('--seconds',type=float,default=1)
    args=parser.parse_args()
    if args.output_dir:
        OUT=pathlib.Path(args.output_dir).resolve();OUT.mkdir(parents=True,exist_ok=True);c.OUT=OUT;c.base.OUT=OUT
    if args.action=='larger':c.EXTRA_PORTS=[9003]
    if args.action=='preflight':preflight()
    elif args.action=='performance':performance()
    elif args.action=='larger':larger_matrix()
    elif args.action=='verify':verify_transactions()
    else:
        c.base.refresh()
        if args.action=='smoke':assert run(True,'carriage-smoke',args.seconds,args.level)['valid']
        elif args.action=='oversell':
            for i in range(3):burst([9000],f'oversell-{i}',True)
        else:
            guard_check()
            burst([9002,9012],'two-instance-50')
