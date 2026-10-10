"""Persistent JMeter supervisor. Never resets business inventory wholesale."""
import argparse,base64,csv,hashlib,http.server,importlib.util,json,math,os,pathlib,statistics,subprocess,threading,time
HERE=pathlib.Path(__file__).resolve().parent;OUT=pathlib.Path(os.environ.get('WARM_OUTPUT',str(HERE/'results/warm-purchase')))
spec=importlib.util.spec_from_file_location('carriage',HERE/'run-carriage.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
c=m.c;m.OUT=OUT;c.OUT=OUT;c.base.OUT=OUT;c.EXTRA_PORTS=[9003]
c.NAMES+=['my12306.purchase.metadata.prepare']
def dump(path,value):c.dump(path,value)
def read(path):return list(csv.DictReader(path.open(encoding='utf-8'))) if path.exists() else []
def clean_owned_delay(order_numbers):
    """Only known, canceled and deleted orders; atomic with the live consumer."""
    if not order_numbers:return 0
    assert all(value.isdigit() and len(value)==19 for value in order_numbers)
    queue='my12306-order-service:delay-close-order-queue'
    lua='''
local wanted={}
for _,sn in ipairs(ARGV) do
 local value=string.char(3)..sn:sub(1,-2)..string.char(sn:byte(-1)+128)
 wanted[value]=true
end
local removed=0
for _,v in ipairs(redis.call('LRANGE',KEYS[1],0,-1)) do
 local _,value=struct.unpack('Bc0Lc0',v)
 if wanted[value] then
  removed=removed+redis.call('LREM',KEYS[1],0,v)
  redis.call('ZREM',KEYS[2],v)
 end
end
for value,_ in pairs(wanted) do removed=removed+redis.call('LREM',KEYS[3],0,value) end
local remaining=0
for _,v in ipairs(redis.call('LRANGE',KEYS[1],0,-1)) do
 local _,value=struct.unpack('Bc0Lc0',v)
 if wanted[value] then remaining=remaining+1 end
end
for _,value in ipairs(redis.call('LRANGE',KEYS[3],0,-1)) do
 if wanted[value] then remaining=remaining+1 end
end
return {removed,remaining}
'''
    removed=0
    for start in range(0,len(order_numbers),200):
        result=c.base.redis('EVAL',lua,3,'redisson_delay_queue:{'+queue+'}',
                            'redisson_delay_queue_timeout:{'+queue+'}',queue,*order_numbers[start:start+200])
        assert int(result[1])==0,'Owned delayed task remained'
        removed+=int(result[0])
    return removed
def command(value):
    temporary=OUT/'command.next';dump(temporary,value);temporary.replace(OUT/'command.json')
def launch():
    if (OUT/'client-requests.csv').exists():
        import shutil
        archive=OUT/'injector-sessions'/str(time.time_ns());archive.mkdir(parents=True)
        for name in ['client-requests.csv','persistent.jtl','injector.log','injector.stdout.log','injector.gc.log','injector-resources.jsonl','injector-process.json']:
            if (OUT/name).exists():shutil.copy2(OUT/name,archive/name)
    for name in ['injector-stopped.flag','injector-error.txt']:
        if (OUT/name).exists():(OUT/name).unlink()
    classes=c.ROOT/'12306/my12306/services/ticket-services/target/warm-sampler-classes';classes.mkdir(exist_ok=True)
    cp=str(c.base.JMETER/'lib/*')+';'+str(c.base.JMETER/'lib/ext/*')
    subprocess.run([str(pathlib.Path(c.base.JAVA).with_name('javac.exe')),'-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(HERE/'diagnostics/WarmPurchaseSampler.java')],check=True)
    jar=classes.parent/'warm-sampler.jar';subprocess.run([str(pathlib.Path(c.base.JAVA).with_name('jar.exe')),'cf',str(jar),'-C',str(classes),'.'],check=True)
    xml='''<?xml version="1.0" encoding="UTF-8"?><jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3"><hashTree>
<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Warm persistent purchase" enabled="true"><boolProp name="TestPlan.functional_mode">false</boolProp><boolProp name="TestPlan.serialize_threadgroups">false</boolProp></TestPlan><hashTree>
<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="Persistent100" enabled="true"><stringProp name="ThreadGroup.on_sample_error">continue</stringProp><elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop"><boolProp name="LoopController.continue_forever">false</boolProp><stringProp name="LoopController.loops">-1</stringProp></elementProp><stringProp name="ThreadGroup.num_threads">100</stringProp><stringProp name="ThreadGroup.ramp_time">0</stringProp><boolProp name="ThreadGroup.scheduler">false</boolProp></ThreadGroup><hashTree>
<JavaSampler guiclass="JavaTestSamplerGui" testclass="JavaSampler" testname="Purchase" enabled="true"><stringProp name="classname">perfdiag.WarmPurchaseSampler</stringProp><elementProp name="arguments" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments"><collectionProp name="Arguments.arguments"/></elementProp></JavaSampler><hashTree/>
</hashTree></hashTree></hashTree></jmeterTestPlan>'''
    (OUT/'persistent.jmx').write_text(xml,'utf-8')
    if (OUT/'command.json').exists():(OUT/'command.json').unlink()
    c.base.refresh()
    injector_heap=int(os.environ.get('WARM_INJECTOR_HEAP','256'))
    args=[c.base.JAVA,'-Xms64m',f'-Xmx{injector_heap}m','-Xss512k',r'-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix',f'-Xlog:gc:file={OUT/"injector.gc.log"}',
          '-jar',str(c.base.JMETER/'bin/ApacheJMeter.jar'),'-n','-t',str(OUT/'persistent.jmx'),'-l',str(OUT/'persistent.jtl'),'-j',str(OUT/'injector.log'),
          '-Juser.classpath='+str(jar),'-Jwarm.dir='+base64.b64encode(str(OUT).encode()).decode(),'-Jjmeter.save.saveservice.response_message=true']
    with (OUT/'injector.stdout.log').open('w') as output:
        process=subprocess.Popen(args,stdout=output,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    dump(OUT/'injector-process.json',dict(pid=process.pid,args=args,heapMB=injector_heap))
    deadline=time.monotonic()+40
    while not (OUT/'client-requests.csv').exists():
        if process.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Persistent injector failed to start')
        time.sleep(.3)

def guard():
    if (OUT/'injector-error.txt').exists():raise RuntimeError('Injector error: '+(OUT/'injector-error.txt').read_text('utf-8'))
    path=OUT/'machine-resources.jsonl'
    if path.exists():
        samples=[json.loads(line) for line in path.read_text('utf-8-sig').splitlines()[-3:]]
        if len(samples)==3 and all(x['freeMemoryGB']<.5 and x['pageReadsPerSec']>0 for x in samples):raise RuntimeError('Persistent low memory and paging; stop and clean')
    try:c.runtime_guard()
    except RuntimeError as error:
        if 'unhealthy' not in str(error):raise
        # A single busy endpoint timeout is not proof of an unhealthy service.
        import urllib.request
        for attempt in range(2):
            try:
                for port in [9000,9001,9002,9003]:
                    with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health',timeout=3) as response:
                        if json.load(response).get('status')!='UP':raise RuntimeError('Not UP')
                return
            except Exception:
                time.sleep(.5)
        raise RuntimeError('Service failed three consecutive health checks') from error

def phase(tag,level,seconds=1,per_worker=0,cap=405,capture=True,url='http://127.0.0.1:9000/api/ticket-service/ticket/purchase',business=True):
    tag=os.environ.get('WARM_RUN_PREFIX','')+tag
    if (OUT/(tag+'-done.json')).exists():raise RuntimeError('Phase tag already exists; preserve original data')
    before=c.base.ticket_ids() if business else set();m.inventory_clean()
    traces_before=max((int(row['id']) for row in read(OUT/'traces.csv')),default=0)
    flag=OUT/'capture.flag'
    if capture:flag.touch()
    elif flag.exists():flag.unlink()
    time.sleep(.3)
    metrics_before=c.metrics();extra_before=c.extra_metrics();db_before=c.snapshot_db()
    resources=[];stop=threading.Event()
    def sampler():
        while not stop.is_set():
            try:resources.append(dict(time=time.time(),ticket=c.metrics(),order=c.extra_metrics()))
            except Exception as error:resources.append(dict(time=time.time(),error=type(error).__name__))
            stop.wait(1)
    collector=threading.Thread(target=sampler,daemon=True);collector.start()
    completed=False
    try:
        command(dict(id=tag,level=level,seconds=seconds,perWorker=per_worker,cap=cap,url=url))
        deadline=time.monotonic()+160
        while not (OUT/(tag+'-done.json')).exists():
            guard()
            if time.monotonic()>deadline:raise RuntimeError('Phase timeout '+tag)
            time.sleep(.3)
        time.sleep(.4)
        done=json.loads((OUT/(tag+'-done.json')).read_text('utf-8'));requests=[r for r in read(OUT/'client-requests.csv') if r['phase']==tag]
        traces=[r for r in read(OUT/'traces.csv') if int(r['id'])>traces_before]
        locks=[r for r in read(OUT/'locks.csv') if int(r['id'])>traces_before]
        assert len(requests)==done['sent'] and requests
        success=[r for r in requests if r['classification']=='OK'];http=[int(r['httpNs'])/1e6 for r in success]
        start=done['startMs'];end=max(float(r['completeMs']) for r in requests);elapsed=(end-start)/1000
        db_after=c.snapshot_db();metrics_after=c.metrics();extra_after=c.extra_metrics()
        new=c.base.ticket_ids()-before if business else set();available=sum(x==0 for x in c.base.inventory().values())
        accounting={}
        if business:
            assert new
            ids=','.join(map(str,new));accounting=dict(tickets=len(new))
            unique=c.base.sql(f'SELECT COUNT(DISTINCT order_sn),COUNT(DISTINCT train_id,start_station,end_station,seat_type,carriage_number,seat_number) FROM 12306_ticket.t_ticket WHERE id IN ({ids});')[0]
            accounting.update(orders=int(unique[0]),uniqueSeats=int(unique[1]),occupied=810-available)
            sns=','.join("'"+r['orderSn']+"'" for r in requests if r['orderSn'])
            accounting['orderRows']=int(c.base.sql(f'SELECT COUNT(*) FROM 12306_order.t_order WHERE order_sn IN ({sns});')[0][0])
            accounting['orderItems']=int(c.base.sql(f'SELECT COUNT(*) FROM 12306_order.t_order_item WHERE order_sn IN ({sns});')[0][0])
            assert all(x==len(success) for x in accounting.values())
            if capture:assert len({r['id'] for r in traces})==len(success)
        result=dict(tag=tag,level=level,window=seconds,capture=capture,startMs=start,endMs=end,observedSeconds=elapsed,
            sent=len(requests),success=len(success),successQps=len(success)/elapsed,totalQps=len(requests)/elapsed,
            completedInWindow=sum(float(r['completeMs'])<=start+seconds*1000 for r in success),drainSeconds=max(0,elapsed-seconds),
            p50=c.base.percentile(http,.5),p95=c.base.percentile(http,.95),p99=c.base.percentile(http,.99),
            httpMean=statistics.mean(http),classificationMean=statistics.mean(int(r['classifyNs'])/1e6 for r in requests),
            capReached=done['capReached'],accounting=accounting,availableBefore=810,availableAfter=available,
            workerCounts={str(i):sum(int(r['worker'])==i for r in requests) for i in range(level)},
            classes={k:sum(r['classification']==k for r in requests) for k in {r['classification'] for r in requests}},
            metricsBefore=metrics_before,metricsAfter=metrics_after,extraBefore=extra_before,extraAfter=extra_after,dbBefore=db_before,dbAfter=db_after,resources=resources,
            stages={name:dict(mean=statistics.mean(int(r['durationNs'])/1e6 for r in traces if r['stage']==name),median=statistics.median(int(r['durationNs'])/1e6 for r in traces if r['stage']==name)) for name in {r['stage'] for r in traces}})
        dump(OUT/(tag+'-result.json'),result)
        for name,items in [('requests',requests),('traces',traces),('locks',locks)]:
            with (OUT/(tag+'-'+name+'.csv')).open('w',encoding='utf-8',newline='') as f:
                if items:w=csv.DictWriter(f,fieldnames=list(items[0]));w.writeheader();w.writerows(items)
        print(json.dumps({k:result[k] for k in ['tag','sent','successQps','p99','classificationMean','capReached']},ensure_ascii=False),flush=True)
        completed=True
        return result
    finally:
        stop.set();collector.join(15)
        if flag.exists():flag.unlink()
        if not completed:
            # Stop new sends, then wait for already issued calls before cleanup.
            command(dict(id='abort-'+str(time.time_ns()),stop=True))
            deadline=time.monotonic()+125
            while not (OUT/(tag+'-done.json')).exists() and not (OUT/'injector-stopped.flag').exists() and time.monotonic()<deadline:
                time.sleep(.5)
            if not (OUT/(tag+'-done.json')).exists() and not (OUT/'injector-stopped.flag').exists():
                raise RuntimeError('Drain unknown; stop owned services before further cleanup')
        if business:
            new_ids=c.base.ticket_ids()-before
            sns=[r[0] for r in c.base.sql('SELECT DISTINCT order_sn FROM 12306_ticket.t_ticket WHERE id IN ('+','.join(map(str,new_ids))+');')] if new_ids else []
            m.cleanup(before);m.inventory_clean()
            removed=clean_owned_delay(sns)
            dump(OUT/(tag+'-cleanup.json'),dict(orders=len(sns),removedDelayTasks=removed,remainingOwnedDelayTasks=0,availableSeats=810,
                token=c.base.redis('HGET',m.TOKEN,'2')))

class Stub(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length','0')));payload=b'{"code":"0","data":{}}'
        self.send_response(200);self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
    def log_message(self,*args):pass
class Server(http.server.ThreadingHTTPServer):
    request_queue_size=1024;daemon_threads=True
    def handle_error(self,*args):pass
def warm(label,echo=False):
    if echo:
        server=Server(('127.0.0.1',0),Stub);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:phase(label+'-echo',100,per_worker=3,cap=300,capture=False,url=f'http://127.0.0.1:{server.server_port}/echo',business=False)
        finally:server.shutdown();server.server_close()
    previous=None;history=[]
    for index in range(5):
        # A 100-request batch need not be a single cold burst. Repeated calls
        # at 20 concurrency exercise the warmed loop; still <=100 purchases.
        current=phase(f'{label}-warm-{index}',20,per_worker=5,cap=100)
        history.append(current)
        if previous:
            a=current['p50']/previous['p50'];b=current['stages']['purchase.total']['median']/previous['stages']['purchase.total']['median']
            if .85<=a<=1.15 and .85<=b<=1.15:
                dump(OUT/(label+'-warm-stability.json'),dict(stable=True,rounds=len(history),httpRatio=a,methodRatio=b));return
        previous=current
    dump(OUT/(label+'-warm-stability.json'),dict(stable=False,rounds=5))
    raise RuntimeError('Warmup not stable; do not enter formal comparison')
def baseline():
    launch();warm('initial',True)
    calibration=[phase(f'calibration-{level}',level,seconds=.5,cap=200,capture=False) for level in [20,50,100]]
    rate=max(row['successQps'] for row in calibration)
    seconds=min(2,math.floor(305/(rate*1.5)*4)/4)
    if seconds<.25:raise RuntimeError('810 stock cannot support minimum common window')
    dump(OUT/'calibration.json',dict(rate=rate,seconds=seconds))
    block('baseline',0)
def recalibrate(label):
    calibration=[phase(f'{label}-calibration-{level}',level,seconds=.5,cap=200,capture=False) for level in [20,50,100]]
    previous=json.loads((OUT/'calibration.json').read_text('utf-8'));rate=max(previous['rate'],*(r['successQps'] for r in calibration))
    seconds=min(previous['seconds'],2,math.floor(305/(rate*1.5)*4)/4)
    if seconds<.25:raise RuntimeError('810 stock cannot support minimum common window')
    dump(OUT/'calibration.json',dict(rate=rate,seconds=seconds))
    print('Updated common window',seconds,flush=True)
def block(version,index):
    seconds=json.loads((OUT/'calibration.json').read_text('utf-8'))['seconds'];results=[]
    for level in [20,50,100]:
        for capture in [False,True]:
            row=phase(f'{version}-{index}-{level}-{int(capture)}',level,seconds,capture=capture)
            row['version']=version;row['block']=index
            assert not row['capReached'] and row['success']<=405 and row['success']==row['sent']
            results.append(row)
    dump(OUT/f'{version}-{index}-block.json',results)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['baseline','warm','block','calibrate','stop']);parser.add_argument('--version',default='candidate');parser.add_argument('--index',type=int,default=0);a=parser.parse_args()
    if a.action=='baseline':baseline()
    elif a.action=='warm':warm(a.version+str(a.index))
    elif a.action=='block':block(a.version,a.index)
    elif a.action=='calibrate':recalibrate(a.version+str(a.index))
    else:command(dict(id='stop-'+str(time.time_ns()),stop=True))
