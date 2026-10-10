"""Isolate injector overhead against a local stub; no business data or services."""
import argparse,csv,http.server,importlib.util,json,pathlib,statistics,subprocess,threading,time
HERE=pathlib.Path(__file__).resolve().parent
OUT=HERE/'results/http-source';OUT.mkdir(exist_ok=True)
spec=importlib.util.spec_from_file_location('current',HERE/'current-performance.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c);c.OUT=OUT
SERVER_EVENTS=[]
class Stub(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def do_POST(self):
        begin=time.time_ns()/1e6
        self.rfile.read(int(self.headers.get('Content-Length','0')))
        payload=b'{"code":"0","data":{}}'
        self.send_response(200);self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(payload)))
        self.send_header('X-Diag-Server-Begin',str(begin))
        self.send_header('X-Diag-Server-End',str(time.time_ns()/1e6));self.end_headers()
        self.wfile.write(payload);self.wfile.flush()
        SERVER_EVENTS.append(dict(id=self.headers.get('X-Diag-Id'),beginMs=begin,endMs=time.time_ns()/1e6))
    def log_message(self,*args):pass
class Server(http.server.ThreadingHTTPServer):
    request_queue_size=1024;daemon_threads=True
    def handle_error(self,request,client_address):
        # The injector closes pooled keepalive connections at JVM shutdown.
        import sys
        if isinstance(sys.exc_info()[1],ConnectionResetError):return
        super().handle_error(request,client_address)

def plan(control=False):
    source=c.injector_plan().read_text('utf-8')
    source=source.replace('SampleResult.samplePause()', '''SampleResult.samplePause()
int diagSeq=Integer.parseInt(vars.get('diagSeq') ?: '0')
if (diagSeq>=2) { SampleResult.setIgnore();ctx.getThread().stop();return }
vars.put('diagSeq',String.valueOf(diagSeq+1))''')
    source=source.replace(".header('Authorization', fields[1])", ".header('Authorization', fields[1]).header('X-Diag-Id', ctx.getThreadNum()+':'+vars.get('diagSeq'))")
    if control:
        (OUT/'diagnostic.jmx').write_text(source,'utf-8');return
    source=source.replace('def response = client.send(request,', '''long diagStartNs=System.nanoTime()
vars.put('diagSendStartMs',String.valueOf(System.currentTimeMillis()))
def response = client.send(request,''')
    source=source.replace('// Current-HEAD harness', '''long diagEndNs=System.nanoTime()
vars.put('diagSendMs',String.valueOf((diagEndNs-diagStartNs)/1e6))
vars.put('diagSendEndMs',String.valueOf(System.currentTimeMillis()))
vars.put('diagServerBeginMs',response.headers().firstValue('X-Diag-Server-Begin').orElse('0'))
vars.put('diagServerEndMs',response.headers().firstValue('X-Diag-Server-End').orElse('0'))
// Current-HEAD harness''')
    source=source.replace("SampleResult.setSuccessful(cls == 'OK')", "SampleResult.setSuccessful(cls == 'OK')\nvars.put('diagPostMs',String.valueOf((System.nanoTime()-diagEndNs)/1e6))")
    (OUT/'diagnostic.jmx').write_text(source,'utf-8')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--control',action='store_true');options=parser.parse_args()
    (OUT/'users.csv').write_text('username,token,passengerId\n'+''.join(f'stub{i},stub-token,{i}\n' for i in range(400)),'utf-8')
    (OUT/'targets.csv').write_text('train,seat,departure,arrival\n1,2,41,42\n','utf-8')
    plan(options.control);server=Server(('127.0.0.1',0),Stub);worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
    summaries=[]
    try:
        for level in [50,100]:
            tag=f'stub-{level}-'+('control' if options.control else 'tcp-pipe');path=OUT/(tag+'.jtl')
            args=[c.base.JAVA,'-Xms64m','-Xmx256m','-Xss512k',r'-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix','-jar',str(c.base.JMETER/'bin/ApacheJMeter.jar'),
                '-n','-t',str(OUT/'diagnostic.jmx'),'-l',str(path),'-j',str(OUT/(tag+'.log')),
                f'-Jthreads={level}','-Jramp=0','-JwindowSec=30',f'-JrequestCap={level*3}',
                f'-JuserFile={OUT/"users.csv"}',f'-JtargetsFile={OUT/"targets.csv"}',
                f'-JbaseUrl=http://127.0.0.1:{server.server_port}',
                '-Jsample_variables=diagSeq,diagSendMs,diagPostMs,diagSendStartMs,diagSendEndMs,diagServerBeginMs,diagServerEndMs']
            with (OUT/(tag+'.stdout.log')).open('w') as log:subprocess.run(args,stdout=log,stderr=subprocess.STDOUT,timeout=120,check=True,creationflags=subprocess.CREATE_NO_WINDOW)
            data=list(csv.DictReader(path.open(encoding='utf-8-sig')))
            assert len(data)==level*2 and all(row['success']=='true' for row in data)
            for seq in ['1','2']:
                selected=[r for r in data if r['diagSeq']==seq]
                def stats(field):
                    values=[float(r[field]) for r in selected];return dict(mean=statistics.mean(values),median=statistics.median(values),min=min(values),max=max(values))
                if options.control:
                    result=dict(level=level,sequence=seq,count=len(selected),sampler=stats('elapsed'))
                    summaries.append(result);print(json.dumps(result),flush=True);continue
                result=dict(level=level,sequence=seq,count=len(selected),sampler=stats('elapsed'),send=stats('diagSendMs'),post=stats('diagPostMs'),
                    beforeServerMeanMs=statistics.mean(float(r['diagServerBeginMs'])-float(r['diagSendStartMs']) for r in selected),
                    afterServerMeanMs=statistics.mean(float(r['diagSendEndMs'])-float(r['diagServerEndMs']) for r in selected))
                summaries.append(result);print(json.dumps(result),flush=True)
    finally:server.shutdown();server.server_close()
    prefix='control-' if options.control else ''
    (OUT/(prefix+'stub-server-events.json')).write_text(json.dumps(SERVER_EVENTS,indent=2),'utf-8')
    (OUT/(prefix+'stub-summary.json')).write_text(json.dumps(summaries,indent=2),'utf-8')

if __name__=='__main__':main()
