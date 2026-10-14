"""Validate in an isolated MySQL database; never migrate or reset the configured business database.

Requires Python with PyYAML, mysql client, Maven, Java 21. Credentials stay in environment variables.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit
import yaml

MODULE = Path(__file__).resolve().parents[1]
REPORTS = MODULE / 'target' / 'segment-validation'


def run(args, env, log=None):
    completed = subprocess.run(args, cwd=MODULE, env=env, capture_output=True, encoding='utf-8', errors='replace')
    if log:
        log.write_text(completed.stdout + completed.stderr, encoding='utf-8')
    if completed.returncode:
        raise RuntimeError(f'{Path(args[0]).name} failed; see {log or "client error"}')
    return completed.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tests', default='*Test')
    parser.add_argument('--benchmark', action='store_true')
    opts = parser.parse_args()
    cfg = yaml.safe_load((MODULE / 'src/main/resources/application.yml').read_text(encoding='utf-8'))['spring']['datasource']
    endpoint = urlsplit(cfg['url'].removeprefix('jdbc:'))
    source = endpoint.path.lstrip('/')
    if not re.fullmatch(r'[A-Za-z0-9_]+', source):
        raise ValueError('Invalid source database identifier')
    database = 'codex_segment_test_' + str(time.time_ns())
    env = os.environ.copy()
    if os.name == 'nt':
        # JDK 21's Windows selector can fail when its Unix-domain temporary path is available.
        # Force the supported TCP loopback fallback, as the existing project diagnostics do.
        env['JAVA_TOOL_OPTIONS'] = env.get('JAVA_TOOL_OPTIONS','') + ' -Djdk.net.unixdomain.tmpdir=Z:/disable-af-unix'
    env['MYSQL_PWD'] = str(cfg['password'])
    mysql = [shutil.which('mysql'), '--default-character-set=utf8mb4', '--batch', '--raw', '--skip-column-names',
             '-h', endpoint.hostname, '-P', str(endpoint.port or 3306), '-u', cfg['username']]
    if not mysql[0]:
        raise RuntimeError('mysql client unavailable')
    REPORTS.mkdir(parents=True, exist_ok=True)

    def sql(statement):
        p = subprocess.run(mysql, input=statement, env=env, capture_output=True, encoding='utf-8', errors='replace')
        if p.returncode:
            # SQL is generated from validated identifiers and carries no credentials.
            raise RuntimeError('Isolated database operation failed: ' + p.stderr)
        return p.stdout.strip()

    sql(f'CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
    try:
        tables = sql(f'SHOW TABLES FROM `{source}`;').splitlines()
        for table in tables:
            if not re.fullmatch(r't_[a-z_]+', table) or table in {'t_inventory_station', 't_physical_seat', 't_seat_segment', 't_inventory_cache_event'}:
                continue
            sql(f'CREATE TABLE `{database}`.`{table}` LIKE `{source}`.`{table}`;')
            if table != 't_ticket':
                sql(f'INSERT INTO `{database}`.`{table}` SELECT * FROM `{source}`.`{table}`;')
        # Only the newly created copy is reset. Source tables are read-only throughout.
        sql(f'UPDATE `{database}`.t_seat SET seat_status=0;')
        env['TICKET_DB_URL'] = f'jdbc:mysql://{endpoint.hostname}:{endpoint.port or 3306}/{database}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
        env['TICKET_DB_USER'] = cfg['username']
        env['TICKET_DB_PASSWORD'] = str(cfg['password'])
        env['SPRING_DATASOURCE_URL'] = env['TICKET_DB_URL']
        env['SPRING_DATASOURCE_USERNAME'] = cfg['username']
        env['SPRING_DATASOURCE_PASSWORD'] = str(cfg['password'])
        env['MY12306_PAY_NOTIFY_MODE'] = 'feign'
        redis_cfg=yaml.safe_load((MODULE/'src/main/resources/application.yml').read_text(encoding='utf-8'))['spring']['data']['redis']
        env['SEGMENT_REDIS_TEST']='true'
        env['SEGMENT_REDIS_HOST']=str(redis_cfg.get('host','127.0.0.1'))
        env['SEGMENT_REDIS_PORT']=str(redis_cfg.get('port',6379))
        env['SEGMENT_REDIS_PASSWORD']=str(redis_cfg.get('password') or '')
        maven = shutil.which('mvn')
        run([maven, '-q', 'test-compile', '-DskipTests', 'dependency:build-classpath', '-Dmdep.outputFile=target/segment-classpath.txt'], env, REPORTS/'compile.log')
        cp = str(MODULE/'target/classes') + os.pathsep + (MODULE/'target/segment-classpath.txt').read_text().strip()
        tool = 'edu.swu.fcj.my12306.biz.ticketservice.toolkit.InventoryMigrationTool'
        # Preserve the static source conflict report before selecting the valid reference fixture.
        check_report=REPORTS/'source-fixture-check.json'
        check_report.unlink(missing_ok=True)
        probe = subprocess.run([shutil.which('java'), '-cp', cp, tool, 'check',
                                '--report='+str(REPORTS/'source-fixture-check.json')],
                               env=env, capture_output=True, encoding='utf-8', errors='replace')
        (REPORTS/'source-fixture-check.log').write_text(probe.stdout+probe.stderr,encoding='utf-8')
        if not check_report.exists():
            raise RuntimeError('Source inspection failed before generating its conflict report')
        for table in ['t_seat','t_train_station','t_train_station_price','t_train_station_relation']:
            sql(f'DELETE FROM `{database}`.`{table}` WHERE train_id<>1;')
        sql(f'DELETE FROM `{database}`.t_train WHERE id<>1;')
        run([shutil.which('java'), '-cp', cp, tool, 'migrate', '--writers-stopped', '--report='+str(REPORTS/'migration.json')], env, REPORTS/'migration.log')
        test_args = [maven, '-q', 'test', '-Dtest='+opts.tests,
                     '-Dmy12306.inventory.cache-events.enabled=false', '-Dmy12306.ticket.orphan-scan-enabled=false']
        if opts.benchmark:
            test_args += ['-Dsegment.benchmark=true']
        started=time.monotonic()
        report_start=time.time()
        run(test_args, env, REPORTS/'tests.log')
        suites=[]
        for report in (MODULE/'target/surefire-reports').glob('TEST-*.xml'):
            if report.stat().st_mtime < report_start:
                continue
            suite=ET.parse(report).getroot()
            suites.append({'name':suite.attrib['name'],**{key:int(suite.attrib.get(key,0)) for key in ['tests','failures','errors','skipped']}})
        (REPORTS/'run.json').write_text(json.dumps({'database':database,'sourceReadOnly':True,'tests':opts.tests,
               'benchmark':opts.benchmark,'elapsedSeconds':time.monotonic()-started,'suites':suites,
               'totals':{key:sum(s[key] for s in suites) for key in ['tests','failures','errors','skipped']}},indent=2),encoding='utf-8')
        print('Validation passed. Reports: '+str(REPORTS))
    finally:
        # The final target is explicitly checked before dropping this task-owned database.
        if not re.fullmatch(r'codex_segment_test_[0-9]+', database) or database == source:
            raise RuntimeError('Refusing to drop an unexpected database')
        sql(f'DROP DATABASE `{database}`;')


if __name__ == '__main__':
    main()
