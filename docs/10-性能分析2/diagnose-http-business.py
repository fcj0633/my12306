"""Two purchases per thread in one JVM, with distinct users; diagnostic, not capacity."""
import csv,hashlib,importlib.util,json,pathlib,statistics
HERE=pathlib.Path(__file__).resolve().parent;OUT=HERE/'results/http-business'
spec=importlib.util.spec_from_file_location('carriage',HERE/'run-carriage.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
c=m.c;m.OUT=OUT;c.OUT=OUT;c.base.OUT=OUT;c.EXTRA_PORTS=[9003]
c.JMETER_SAMPLE_VARIABLES=['diagSeq','diagSendMs','diagPostMs','diagSendStartMs','diagSendEndMs','diagOrderSn']
original=c.injector_plan
def diagnostic_plan():
    source=original().read_text('utf-8')
    source=source.replace('SampleResult.samplePause()', '''SampleResult.samplePause()
int diagSeq=Integer.parseInt(vars.get('diagSeq') ?: '0')
if (diagSeq>=2) { SampleResult.setIgnore();ctx.getThread().stop();return }
vars.put('diagSeq',String.valueOf(diagSeq+1))''')
    source=source.replace('def fields = lines[tid].split', "def fields = lines[tid+diagSeq*Integer.parseInt(props.getProperty('threads'))].split")
    source=source.replace('def response = client.send(request,', '''long diagStartNs=System.nanoTime()
vars.put('diagSendStartMs',String.valueOf(System.currentTimeMillis()))
def response = client.send(request,''')
    source=source.replace('// Current-HEAD harness', '''long diagEndNs=System.nanoTime()
vars.put('diagSendMs',String.valueOf((diagEndNs-diagStartNs)/1e6))
vars.put('diagSendEndMs',String.valueOf(System.currentTimeMillis()))
// Current-HEAD harness''')
    source=source.replace("SampleResult.setSuccessful(cls == 'OK')", "SampleResult.setSuccessful(cls == 'OK')\nvars.put('diagPostMs',String.valueOf((System.nanoTime()-diagEndNs)/1e6))")
    source=source.replace('def json = new JsonSlurper().parseText(response.body())', "def json = new JsonSlurper().parseText(response.body())\nvars.put('diagOrderSn',String.valueOf(json.data?.orderSn))")
    path=OUT/'diagnostic-purchase.jmx';path.write_text(source,'utf-8');return path
c.injector_plan=diagnostic_plan

def main():
    results=[]
    for level in [50,100]:
        c.base.refresh()
        row=m.run(True,f'http-source-{level}',30,level)
        assert row['valid'] and row['samples']==2*level and row['poolBefore']==810 and row['success']<=405
        results.append(row)
    c.dump(OUT/'diagnostic-rounds.json',results)
    summaries=[]
    for row in results:
        data=list(csv.DictReader((OUT/f"s1-{row['concurrency']}-{row['tag']}.jtl").open(encoding='utf-8')))
        for sequence in ['1','2']:
            selected=[r for r in data if r['diagSeq']==sequence]
            def stats(name):
                values=[float(r[name]) for r in selected]
                return dict(mean=statistics.mean(values),median=statistics.median(values),min=min(values),max=max(values))
            summaries.append(dict(level=row['concurrency'],sequence=sequence,count=len(selected),sampler=stats('elapsed'),send=stats('diagSendMs'),post=stats('diagPostMs')))
    environment=json.loads((OUT/'environment.json').read_text('utf-8'))
    m.inventory_clean()
    assert c.base.sql('SELECT id,seat_status FROM 12306_ticket.t_seat WHERE NOT ('+c.base.POOL+') ORDER BY id;')==environment['outsidePool']
    assert sorted(c.base.ticket_ids())==environment['initialPerfTicketIds']
    assert all(hashlib.sha256((c.ROOT/f'12306/my12306/services/{service}-services/target/{service}-services-0.0.1-SNAPSHOT.jar').read_bytes()).hexdigest()==value for service,value in environment['artifactSha256'].items())
    c.dump(OUT/'client-summary.json',summaries)
    print(json.dumps(summaries,ensure_ascii=False),flush=True)

if __name__=='__main__':main()
