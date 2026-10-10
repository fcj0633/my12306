"""Heap-specific metrics (generic JVM used includes nonheap). Actual times saved."""
import concurrent.futures,json,os,pathlib,time,urllib.request
OUT=pathlib.Path(os.environ['WARM_OUTPUT']);ports={'user':9001,'ticket':9002,'order':9003,'gateway':9000}
def metric(item):
    name,port=item;result={'service':name}
    for metric in ['jvm.memory.used','jvm.memory.max','jvm.gc.pause']:
        try:
            suffix='?tag=area:heap' if metric.startswith('jvm.memory') else ''
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/metrics/{metric}'+suffix,timeout=2) as response:
                result[metric]={row['statistic']:row['value'] for row in json.load(response)['measurements']}
        except Exception as error:result[metric]={'error':type(error).__name__}
    return result
with (OUT/'heap-resources.jsonl').open('a',encoding='utf-8',buffering=1) as output,concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    while not (OUT/'stop-monitor').exists():
        output.write(json.dumps(dict(time=time.time(),services=list(pool.map(metric,ports.items()))))+'\n');time.sleep(1)
