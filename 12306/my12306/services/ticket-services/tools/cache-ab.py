"""Cache A/B runner: task-owned MySQL databases and namespaced real Redis; no business mutations."""
import argparse, hashlib, json, os, queue, re, shutil, subprocess, threading, time, zipfile
from pathlib import Path
from urllib.parse import urlsplit
import yaml
MODULE=Path(__file__).resolve().parents[1]
ART=MODULE/'target/cache-ab'
DOCS=MODULE.parents[3]/'docs/12-cache-optimization/evidence'
MAIN='edu.swu.fcj.my12306.biz.ticketservice.service.cachebench.CacheBenchmarkMain'

def config():
 cfg=yaml.safe_load((MODULE/'src/main/resources/application.yml').read_text(encoding='utf-8'))['spring']
 db=cfg['datasource'];url=urlsplit(db['url'].removeprefix('jdbc:'));source=url.path.lstrip('/')
 if not re.fullmatch(r'[A-Za-z0-9_]+',source):raise RuntimeError('Invalid source database identifier')
 env=os.environ.copy();env['MYSQL_PWD']=str(db['password']);env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' -Djdk.net.unixdomain.tmpdir=Z:/disable-af-unix'
 env['MY12306_PAY_NOTIFY_MODE']='feign';env['MY12306_ORPHAN_SCAN_ENABLED']='false';env['SERVER_PORT']='0';env['SPRING_CLOUD_NACOS_DISCOVERY_ENABLED']='false';env['SPRING_CLOUD_DISCOVERY_ENABLED']='false'
 env['TICKET_DB_USER']=str(db['username']);env['TICKET_DB_PASSWORD']=str(db['password'])
 env['SPRING_DATASOURCE_USERNAME']=str(db['username']);env['SPRING_DATASOURCE_PASSWORD']=str(db['password'])
 env['SEGMENT_REDIS_HOST']=str(cfg['data']['redis']['host']);env['SEGMENT_REDIS_PORT']=str(cfg['data']['redis'].get('port',6379));env['SEGMENT_REDIS_PASSWORD']=str(cfg['data']['redis'].get('password') or '')
 return cfg,url,source,env

def sql(statement):
 cfg,url,source,env=config();db=cfg['datasource']
 result=subprocess.run([shutil.which('mysql'),'--default-character-set=utf8mb4','--batch','--raw','--skip-column-names','-h',url.hostname,'-P',str(url.port or 3306),'-u',db['username']],input=statement,env=env,capture_output=True,encoding='utf-8',errors='replace')
 if result.returncode:raise RuntimeError('Isolated database SQL failed: '+result.stderr)
 return result.stdout.strip()

def classpath(model):
 classes=ART/'baseline/classes' if model=='A' else MODULE/'target/classes'
 return os.pathsep.join([str(classes),str(MODULE/'target/test-classes'),str(MODULE/'src/main/resources'),(MODULE/'target/segment-classpath.txt').read_text().strip()])

def compile_sources():
 _,_,_,env=config();result=subprocess.run([shutil.which('mvn'),'-q','test-compile','-DskipTests','dependency:build-classpath','-Dmdep.outputFile=target/segment-classpath.txt'],cwd=MODULE,env=env,capture_output=True,encoding='utf-8',errors='replace')
 (ART/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8')
 if result.returncode:raise RuntimeError('Compile failed: '+str(ART/'compile.log'))

def freeze():
 ART.mkdir(parents=True,exist_ok=True);DOCS.mkdir(parents=True,exist_ok=True)
 if (ART/'baseline/classes').exists():raise RuntimeError('Baseline is already frozen')
 compile_sources();shutil.copytree(MODULE/'target/classes',ART/'baseline/classes')
 manifest={str(p.relative_to(MODULE/'src/main')).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in (MODULE/'src/main').rglob('*') if p.is_file()}
 (DOCS/'baseline-instrumented-sha256.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
 print('Frozen instrumented baseline',flush=True)

def restore():
 destination=ART/'baseline/classes'
 if destination.exists():raise RuntimeError('Baseline already exists')
 with zipfile.ZipFile(DOCS/'baseline-classes.zip') as archive:
  for info in archive.infolist():
   target=(destination/info.filename).resolve()
   if not target.is_relative_to(destination.resolve()):raise RuntimeError('Unexpected archive path')
  archive.extractall(destination)
 print('Restored immutable baseline classes; external credentials remain in environment')

def prepare():
 cfg,url,source,env=config();ART.mkdir(parents=True,exist_ok=True);DOCS.mkdir(parents=True,exist_ok=True)
 if (ART/'databases.json').exists():raise RuntimeError('An isolated fixture already exists')
 run_id=str(time.time_ns());databases={model:'codex_cache_test_'+run_id+'_'+model for model in ('A','B')}
 (ART/'databases.json').write_text(json.dumps(databases,indent=2))
 for model,name in databases.items():
  sql(f'CREATE DATABASE `{name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
  for table in sql(f'SHOW TABLES FROM `{source}`;').splitlines():
   if not re.fullmatch(r't_[a-z_]+',table) or table in {'t_inventory_station','t_physical_seat','t_seat_segment','t_inventory_cache_event'}:continue
   sql(f'CREATE TABLE `{name}`.`{table}` LIKE `{source}`.`{table}`;')
   if table!='t_ticket':sql(f'INSERT INTO `{name}`.`{table}` SELECT * FROM `{source}`.`{table}`;')
  sql(f'UPDATE `{name}`.t_seat SET seat_status=0;')
  for table in ['t_seat','t_train_station','t_train_station_price','t_train_station_relation','t_train']:sql(f'DELETE FROM `{name}`.`{table}` WHERE '+('id' if table=='t_train' else 'train_id')+'<>1;')
  env['TICKET_DB_URL']=f'jdbc:mysql://{url.hostname}:{url.port or 3306}/{name}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
  tool='edu.swu.fcj.my12306.biz.ticketservice.toolkit.InventoryMigrationTool'
  result=subprocess.run([shutil.which('java'),'-cp',classpath('A'),tool,'migrate','--writers-stopped','--report='+str(DOCS/f'migration-{model}.json')],env=env,capture_output=True,encoding='utf-8',errors='replace')
  (ART/f'migration-{model}.log').write_text(result.stdout+result.stderr,encoding='utf-8')
  if result.returncode:raise RuntimeError('Isolated migration failed')
 print('Prepared two isolated inventories; source read-only',flush=True)

class Driver:
 def __init__(self,model,tag):
  cfg,url,source,env=config();name=json.loads((ART/'databases.json').read_text())[model]
  if not re.fullmatch(r'codex_cache_test_[0-9]+_[AB]',name) or name==source:raise RuntimeError('Unexpected fixture database')
  env['SPRING_DATASOURCE_URL']=f'jdbc:mysql://{url.hostname}:{url.port or 3306}/{name}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
  env['CACHE_TEST_PREFIX']='codex:cache-ab:'+tag+'_'+model+':'
  self.model=model;self.tag=tag;self.queue=queue.Queue();self.log=(ART/f'{tag}-{model}.log').open('w',encoding='utf-8')
  self.process=subprocess.Popen([str(Path(os.environ['JAVA_HOME'])/'bin/java.exe') if os.environ.get('JAVA_HOME') else shutil.which('java'),'-Xms128m','-Xmx512m','-cp',classpath(model),MAIN,model],cwd=MODULE,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1)
  def reader():
   for line in self.process.stdout:
    self.log.write(line);self.log.flush()
    if line.startswith('CACHE_AB_'):self.queue.put(line.strip())
   self.queue.put('PROCESS_EXIT '+str(self.process.poll()))
  threading.Thread(target=reader,daemon=True).start()
  ready=self.queue.get(timeout=100)
  if not ready.startswith('CACHE_AB_READY'):raise RuntimeError('Benchmark startup failed; see '+str(ART/f'{tag}-{model}.log'))
 def run(self,scenario,**kwargs):
  self.process.stdin.write(json.dumps({'scenario':scenario,**kwargs})+'\n');self.process.stdin.flush()
  line=self.queue.get(timeout=kwargs.get('seconds',60)+120)
  if not line.startswith('CACHE_AB_RESULT '):raise RuntimeError(line+'; see benchmark log')
  result=json.loads(line.split(' ',1)[1]);result['runId']=self.tag;path=DOCS/f'{self.model}-{scenario}-{kwargs.get("round",0)}-{time.time_ns()}.json';path.write_text(json.dumps(result,ensure_ascii=False),encoding='utf-8')
  summary={k:v for k,v in result.items() if k not in ('operations','metrics','propagation')}
  if 'operations' in result:summary['operations']={k:{a:b for a,b in v.items() if a!='latenciesMicros'} if isinstance(v,dict) else v for k,v in result['operations'].items()}
  print(json.dumps(summary,ensure_ascii=True),flush=True);return result
 def close(self):
  if self.process.poll() is None:
   try:self.process.stdin.write('{"scenario":"exit"}\n');self.process.stdin.flush();self.process.wait(timeout=25)
   except Exception:
    if os.name=='nt':subprocess.run(['taskkill','/PID',str(self.process.pid),'/T','/F'],capture_output=True)
    else:self.process.terminate()
    self.process.wait(timeout=10)
  self.log.close()

def proof():
 driver=Driver('A','proof_'+str(time.time_ns()))
 try:
  for scenario in ['empty-city','missing-train','empty-search']:driver.run(scenario,seconds=2,concurrency=20,round=0)
  for scenario in ['burst-remaining','burst-city','burst-cold']:driver.run(scenario,concurrency=50,waves=3)
  result=driver.run('refill-race')
  if not result['stalePublished']:raise RuntimeError('Expected the baseline refill race to reproduce')
  driver.run('hot-http',seconds=60,concurrency=50,round=0)
 finally:driver.close()

def matrix(args):
 compile_sources();current=ART/'current-run.json'
 parameters={k:getattr(args,k) for k in ['models','rounds','seconds','concurrency','scenarios','start_round','http_warm_seconds']}
 if args.resume:
  state=json.loads(current.read_text());tag=state['runId']
  if state['parameters']!=parameters:raise RuntimeError('Resume parameters differ from the interrupted run')
 else:
  tag='matrix_'+str(time.time_ns());state={'runId':tag,'parameters':parameters,'completed':[],'postReboot':True}
 def checkpoint():
  temporary=current.with_suffix('.tmp');temporary.write_text(json.dumps(state,indent=2));temporary.replace(current)
  (DOCS/('run-'+tag+'.json')).write_text(json.dumps(state,indent=2))
 checkpoint();drivers={}
 try:
  for model in args.models.split(','):drivers[model]=Driver(model,tag)
  for round_index in range(args.start_round,args.start_round+args.rounds):
   for scenario in args.scenarios.split(','):
    for model in (list(drivers) if round_index%2 else list(reversed(drivers))):
     key=f'{model}/{scenario}/{round_index}'
     if key in state['completed']:continue
     drivers[model].run(scenario,seconds=args.seconds,concurrency=args.concurrency,round=round_index,httpWarmSeconds=args.http_warm_seconds)
     state['completed'].append(key);checkpoint()
 finally:
  for driver in drivers.values():driver.close()


def cleanup():
 _,_,source,_=config();state=ART/'databases.json'
 if not state.exists():return
 for name in json.loads(state.read_text()).values():
  if not re.fullmatch(r'codex_cache_test_[0-9]+_[AB]',name) or name==source:raise RuntimeError('Refusing unexpected database removal')
  sql(f'DROP DATABASE IF EXISTS `{name}`;')
 state.unlink();print('Removed only task-owned isolated databases')

def main():
 parser=argparse.ArgumentParser();parser.add_argument('command',choices=['freeze','restore','prepare','proof','matrix','cleanup']);parser.add_argument('--http-warm-seconds',type=float,default=10);parser.add_argument('--resume',action='store_true');parser.add_argument('--start-round',type=int,default=1);parser.add_argument('--models',default='A,B');parser.add_argument('--rounds',type=int,default=3);parser.add_argument('--seconds',type=float,default=60);parser.add_argument('--concurrency',type=int,default=50)
 parser.add_argument('--scenarios',default='hot-service,hot-http,empty-city,missing-train,empty-search,unique-train,mixed,overlap-mixed')
 args=parser.parse_args()
 if args.command=='matrix':matrix(args)
 else:globals()[args.command]()
if __name__=='__main__':main()
