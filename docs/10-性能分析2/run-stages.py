"""Current version only, twenty clients; request-scoped agent on/off and client control."""
import argparse,csv,importlib.util,json,pathlib,time
HERE=pathlib.Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('current',HERE/'current-performance.py')
c=importlib.util.module_from_spec(s);s.loader.exec_module(c)
OUT=HERE/'results/stages';c.OUT=OUT;c.base.OUT=OUT
original_plan=c.injector_plan
MODE='shared'
def plan():
    path=original_plan();text=path.read_text('utf-8')
    if MODE=='per-thread':
        point="def lines = props.get('perf.user.lines')"
        text=text.replace(point,"""def localClient=vars.getObject('stage.http.client')
if(localClient==null){
 localClient=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
 vars.putObject('stage.http.client',localClient)
}
client=localClient
"""+point)
    path.write_text(text,encoding='utf-8');return path
c.injector_plan=plan
def traces():
    return list(csv.DictReader((OUT/'traces.csv').open(encoding='utf-8')))
def run(mode,enabled,tag,seconds=8):
    global MODE;MODE=mode
    before=max((int(r['id']) for r in traces()),default=0)
    flag=OUT/'capture.flag'
    if enabled:flag.touch()
    elif flag.exists():flag.unlink()
    time.sleep(.3)
    try:r=c.run('s1',20,seconds,tag)
    finally:
        if flag.exists():flag.unlink()
    time.sleep(.4)
    data=[row for row in traces() if int(row['id'])>before]
    ids={row['id'] for row in data}
    if enabled:
        assert len(ids)==r['success'],f'Trace count {len(ids)} != successful purchases {r["success"]}'
        required={'purchase.total','seat.lock-wait','seat.unlock-safe','tx.body','tx.proxy','jdbc.commit','jdbc.seat-select'}
        for trace_id in ids:
            present={row['stage'] for row in data if row['id']==trace_id}
            assert required<=present,f'Missing diagnostics {required-present}'
    else:assert not ids,'Disabled probe unexpectedly captured requests'
    r.update(probeEnabled=enabled,clientMode=mode,traceCount=len(ids))
    c.dump(OUT/(r['tag']+'-stage-result.json'),r)
    with (OUT/(r['tag']+'-traces.csv')).open('w',encoding='utf-8',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=list(traces()[0]) if traces() else ['id'])
        writer.writeheader();writer.writerows(data)
    return r
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['smoke','matrix','client','confirm'])
    a=p.parse_args()
    deadline=time.time()+120
    while True:
        try:
            assert c.base.api(9002,'/actuator/health')['status']=='UP'
            if (OUT/'traces.csv').exists():break
        except Exception:pass
        if time.time()>deadline:raise RuntimeError('Diagnostic Ticket not ready')
        time.sleep(2)
    c.base.refresh()
    if a.action=='smoke':
        result=run('shared',True,'stage-smoke',1)
        print('Captured complete stage traces',result['traceCount'])
    elif a.action=='matrix':
        for i in range(6):run('shared',bool(i%2),f'stage-warm-{i}',5)
        results=[]
        for i in range(3):
            for enabled in [False,True]:
                r=run('shared',enabled,f'stage-shared-{int(enabled)}-{i}')
                assert r['valid'];results.append(r);c.dump(OUT/'stage-matrix.json',results)
    elif a.action=='client':
        results=json.loads((OUT/'stage-matrix.json').read_text('utf-8'))
        for i in range(2):run('per-thread',False,f'stage-per-thread-warm-{i}',5)
        for i in range(3):
            r=run('per-thread',True,f'stage-per-thread-{i}')
            assert r['valid'];results.append(r);c.dump(OUT/'stage-matrix.json',results)
    else:
        for i in range(4):run('shared',True,f'stage-confirm-warm-{i}',5)
        results=[]
        modes=[('shared',False),('shared',True),('per-thread',True)]
        # Latin square: each setting occupies each position once; equal mean order.
        for block in range(3):
            for index in range(3):
                mode,enabled=modes[(index+block)%3]
                r=run(mode,enabled,f'stage-confirm-{block}-{mode}-{int(enabled)}')
                assert r['valid'];results.append(r);c.dump(OUT/'stage-confirm-matrix.json',results)
