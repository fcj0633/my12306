"""One bounded real CDC experiment. All mutations/cleanup restricted to task-owned resources."""
import importlib.util, json, os, re, secrets, shutil, subprocess, time
from pathlib import Path

MODULE=Path(__file__).resolve().parents[1]
TICKET=MODULE.parent/'ticket-services'
ROOT=MODULE.parents[3]
ART=MODULE/'target/simple-chain'
DOC=ROOT/'docs/13-cdc-cache-invalidation/evidence'
spec=importlib.util.spec_from_file_location('cache_fixture',TICKET/'tools/cache-ab.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)

def run(args,env,cwd=None,timeout=180,log=None):
    result=subprocess.run(args,env=env,cwd=cwd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    if log:log.write_text(result.stdout+result.stderr,encoding='utf-8')
    if result.returncode:raise RuntimeError('Command failed; see '+str(log or args[0]))
    return result.stdout

def main():
    ART.mkdir(parents=True,exist_ok=True); DOC.mkdir(parents=True,exist_ok=True)
    cfg,url,source,env=fixture.config();stamp=str(time.time_ns())
    database='codex_cdc_test_'+stamp;user='cdc_'+stamp[-15:];password=secrets.token_hex(16)
    project='codex-cdc-'+stamp;env.update(CDC_DATABASE=database,CDC_MYSQL_USER=user,CDC_MYSQL_PASSWORD=password,
        CDC_CONFIG_DIR=str(ART.resolve()).replace('\\','/'),CDC_SLAVE_ID=stamp[-8:],CDC_CANAL_PORT='11113',
        CDC_NAMESRV_PORT='19876',CDC_BROKER_PORT='10991',CDC_MYSQL_ADDRESS='host.docker.internal:'+str(url.port or 3306))
    if url.hostname not in ('127.0.0.1','localhost'):env['CDC_MYSQL_ADDRESS']=url.hostname+':'+str(url.port or 3306)
    compose=['docker','compose','-p',project,'-f',str(MODULE/'deploy/compose.yml')]
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java.exe');bridge=None;started=False;created=False
    (ART/'owned-resources.json').write_text(json.dumps({'database':database,'user':user,'dockerProject':project}),encoding='utf-8')
    try:
        for module in (MODULE,TICKET):
            run([shutil.which('mvn'),'-q','test-compile','-DskipTests','dependency:build-classpath','-Dmdep.outputFile=target/cdc-classpath.txt'],env,cwd=module,log=ART/(module.name+'-compile.log'))
        fixture.sql(f'CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;');created=True
        for table in fixture.sql(f'SHOW TABLES FROM `{source}`;').splitlines():
            if not re.fullmatch('t_[a-z_]+',table) or table in {'t_inventory_station','t_physical_seat','t_seat_segment','t_inventory_cache_event'}:continue
            fixture.sql(f'CREATE TABLE `{database}`.`{table}` LIKE `{source}`.`{table}`;')
            if table!='t_ticket':fixture.sql(f'INSERT INTO `{database}`.`{table}` SELECT * FROM `{source}`.`{table}`;')
        fixture.sql(f'UPDATE `{database}`.t_seat SET seat_status=0;')
        for table in ['t_seat','t_train_station','t_train_station_price','t_train_station_relation','t_train']:
            fixture.sql(f'DELETE FROM `{database}`.`{table}` WHERE '+('id' if table=='t_train' else 'train_id')+'<>1;')
        env['TICKET_DB_URL']=f'jdbc:mysql://{url.hostname}:{url.port or 3306}/{database}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
        def cp(module,test=False):
            return os.pathsep.join([str(module/'target/classes'),*([str(module/'target/test-classes')] if test else []),str(module/'src/main/resources'),(module/'target/cdc-classpath.txt').read_text().strip()])
        run([java,'-cp',cp(TICKET,True),'edu.swu.fcj.my12306.biz.ticketservice.toolkit.InventoryMigrationTool','migrate','--writers-stopped','--report='+str(DOC/'migration.json')],env,log=ART/'migration.log')
        fixture.sql(f"CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '{user}'@'%'; GRANT SELECT ON `{database}`.* TO '{user}'@'%';")
        props=(MODULE/'deploy/instance.properties.example').read_text().replace('REPLACE_WITH_ENV_GENERATED_USER',user).replace('REPLACE_WITH_ENV_GENERATED_PASSWORD',password).replace('REPLACE_WITH_DATABASE',database).replace('host.docker.internal:3306',env['CDC_MYSQL_ADDRESS']).replace('19061',env['CDC_SLAVE_ID'])
        (ART/'instance.properties').write_text(props,encoding='utf-8')
        (ART/'broker.conf').write_text((MODULE/'deploy/broker.conf.example').read_text(),encoding='utf-8')
        # Store meta.dat in a dedicated persistent mount, rather than the image layer.
        started=True
        run(compose+['up','-d'],env,timeout=240,log=ART/'compose-up.log')
        ready=False
        for _ in range(60):
            ids=run(compose+['ps','-q','broker'],env).strip()
            if ids:
                check=subprocess.run(['docker','exec',ids,'sh','-c','test -f /home/rocketmq/logs/rocketmqlogs/broker.log && grep -q "boot success" /home/rocketmq/logs/rocketmqlogs/broker.log'],capture_output=True)
                if check.returncode==0:ready=True;break
            time.sleep(1)
        if not ready:raise RuntimeError('Test broker did not become ready')
        env.update(CDC_NAMESRV='127.0.0.1:19876',CDC_CANAL_HOST='127.0.0.1',CDC_TOPIC='codex-inventory-cdc-'+stamp,
            CDC_PRODUCER_GROUP='codex-cdc-pg-'+stamp,MY12306_CDC_ENABLED='true',MY12306_CDC_DATABASE=database,
            MY12306_CDC_NAMESRV='127.0.0.1:19876',MY12306_CDC_TOPIC='codex-inventory-cdc-'+stamp,
            MY12306_CDC_CONSUMER_GROUP='codex-cdc-cg-'+stamp,CACHE_TEST_PREFIX='codex:cdc:'+stamp+':',SPRING_DATASOURCE_URL=env['TICKET_DB_URL'])
        run(['docker','exec',ids,'sh','mqadmin','updateTopic','-n','namesrv:9876','-b','127.0.0.1:10991',
             '-t',env['CDC_TOPIC'],'-r','4','-w','4'],env,log=ART/'create-topic.log')
        # updateTopic changes Broker metadata first; NameServer registration can lag by one period.
        for _ in range(30):
            route=subprocess.run(['docker','exec',ids,'sh','mqadmin','topicRoute','-n','namesrv:9876','-t',env['CDC_TOPIC']],
                env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=15)
            if 'queueDatas' in route.stdout and '127.0.0.1:10991' in route.stdout:
                (ART/'topic-route.json').write_text(route.stdout,encoding='utf-8');break
            time.sleep(1)
        else:raise RuntimeError('Test topic did not register with NameServer')
        bridgeLog=(ART/'bridge.log').open('w',encoding='utf-8')
        bridge=subprocess.Popen([java,'-Xms128m','-Xmx384m','-cp',cp(MODULE),'edu.swu.fcj.my12306.cdc.CdcApplication'],env=env,cwd=MODULE,stdout=bridgeLog,stderr=subprocess.STDOUT)
        for _ in range(60):
            bridgeLog.flush()
            if 'CDC_BRIDGE_READY' in (ART/'bridge.log').read_text(encoding='utf-8',errors='replace'):break
            if bridge.poll() is not None:raise RuntimeError('Bridge exited; see bridge.log')
            time.sleep(1)
        else:raise RuntimeError('Canal connection timeout; see bridge.log')
        # Allow Canal to establish its initial binlog position before the two test commits.
        time.sleep(3)
        output=run([java,'-Xms128m','-Xmx512m','-cp',cp(TICKET,True),'edu.swu.fcj.my12306.biz.ticketservice.mq.CdcSmokeMain'],env,timeout=120,log=ART/'ticket.log')
        lines=[line.removeprefix('CDC_SMOKE_RESULT ') for line in output.splitlines() if line.startswith('CDC_SMOKE_RESULT ')]
        if len(lines)!=1:raise RuntimeError('Smoke result missing; see ticket.log')
        result=json.loads(lines[0]);result['runId']=stamp
        (DOC/'simple-chain.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
    finally:
        if bridge is not None:
            bridge.terminate()
            try:bridge.wait(timeout=15)
            except subprocess.TimeoutExpired:bridge.kill();bridge.wait(timeout=5)
            bridgeLog.close()
        try:
            if started:
                try:run(compose+['logs','--no-color'],env,log=ART/'containers.log')
                finally:run(compose+['down','-v'],env,log=ART/'compose-down.log')
        finally:
            try:
                if created:
                    if not re.fullmatch('codex_cdc_test_[0-9]+',database) or database==source:raise RuntimeError('Refusing cleanup outside owned fixture')
                    fixture.sql(f'DROP DATABASE `{database}`; DROP USER IF EXISTS \'{user}\'@\'%\';')
            finally:
                if (ART/'instance.properties').exists():(ART/'instance.properties').unlink()

if __name__=='__main__':main()
