"""Reproduce the 810-seat experiment without resetting business inventory.

Credentials are read from existing local configuration, passed through the child
environment, and never written to result files or command-line arguments.
"""
import argparse
import ast
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[4]
PERF = Path(__file__).resolve().parents[1]
OUT = PERF / 'results' / 'seat-allocation-810'
SOURCE = ROOT / 'datasource/originalProject/12306/resources/data/12306-springcloud-ticket.sql'
BENCH_DB = '12306_seat_allocator_bench_20261007'
INDEX_COLUMNS = ['train_id', 'seat_type', 'seat_status', 'start_station', 'end_station',
                 'del_flag', 'carriage_number', 'seat_number']


def local_db():
    spec = importlib.util.spec_from_file_location('existing_experiment',
        Path(__file__).with_name('stock-precheck-ab.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def snapshot(db):
    # Timestamp changes caused by existing purchase regression tests are excluded;
    # physical seat identity/status and all existing trade rows must be preserved.
    queries = {
        'seats': 'SELECT id,train_id,carriage_number,seat_number,seat_type,start_station,end_station,price,seat_status,del_flag FROM 12306_ticket.t_seat ORDER BY id;',
        'tickets': 'SELECT * FROM 12306_ticket.t_ticket ORDER BY id;',
        'orders': 'SELECT * FROM 12306_order.t_order ORDER BY id;',
        'items': 'SELECT * FROM 12306_order.t_order_item ORDER BY id;'
    }
    return {name: dict(rows=len(rows), sha256=hashlib.sha256(json.dumps(rows,
            ensure_ascii=False, separators=(',', ':')).encode()).hexdigest())
            for name, query in queries.items() for rows in [db.sql(query)]}


def prepare(db):
    OUT.mkdir(parents=True, exist_ok=True)
    text = SOURCE.read_text('utf-8-sig')
    rows = []
    for block in re.finditer(r'INSERT INTO `t_seat`\s*\([^;]+?\)\s*VALUES\s*(.*?);', text, re.S):
        for item in re.findall(r'\(\s*\d+.*?\)', block.group(1), re.S):
            row = ast.literal_eval(item.replace('NULL', 'None'))
            if len(row) != 12:
                raise RuntimeError('Unexpected source seat column count')
            rows.append(row)
    target = [r for r in rows if r[1] == 1 and r[4] == 2 and r[5:7] == ('北京南', '宁波') and r[11] == 0]
    if len(rows) != 27480 or sum(r[1] == 1 for r in rows) != 9600 or len(target) != 810 or any(r[8] != 0 for r in target):
        raise RuntimeError('Source is not the agreed historical dataset')
    (OUT / 'source-seats.json').write_text(json.dumps(rows, ensure_ascii=False), encoding='utf-8')
    meta = dict(source=str(SOURCE), sourceSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
                sourceRows=len(rows), targetRows=len(target), businessBefore=snapshot(db))
    (OUT / 'source-metadata.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding='utf-8')
    return meta


def apply_index(db):
    existing = db.sql("SELECT COLUMN_NAME FROM information_schema.statistics WHERE TABLE_SCHEMA='12306_ticket' AND TABLE_NAME='t_seat' AND INDEX_NAME='idx_seat_allocate' ORDER BY SEQ_IN_INDEX;")
    if existing:
        if [r[0] for r in existing] != INDEX_COLUMNS:
            raise RuntimeError('Existing business index definition differs; not overwriting')
        print('Business index already matches', flush=True)
    else:
        db.sql('ALTER TABLE 12306_ticket.t_seat ADD INDEX idx_seat_allocate (' + ','.join(INDEX_COLUMNS) + '), ALGORITHM=INPLACE, LOCK=NONE;')
        print('Applied business index; inventory not reset', flush=True)
    plan = db.sql('EXPLAIN ANALYZE SELECT id,carriage_number,seat_number FROM 12306_ticket.t_seat WHERE ' + db.POOL + ' AND seat_status=0 ORDER BY carriage_number,seat_number LIMIT 1;')
    text = '\n'.join(r[0] for r in plan)
    (OUT / 'business-natural-plan.txt').write_text(text.replace('\\n', '\n'), encoding='utf-8')
    if 'idx_seat_allocate' not in text or re.search(r'->\s*Sort:', text):
        raise RuntimeError('Business query did not choose the expected ordered index')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['run', 'write-check', 'apply-index', 'verify'])
    args = parser.parse_args()
    db = local_db()
    if args.action in ['run', 'write-check']:
        meta = prepare(db) if args.action == 'run' else json.loads((OUT / 'source-metadata.json').read_text('utf-8'))
        env = dict(os.environ, MY12306_SEAT_BENCH='true', MY12306_SEAT_DB_PASSWORD=db.DB_PASS,
                   MY12306_SEAT_BENCH_RESULTS=str(OUT), MY12306_SEAT_SOURCE_SHA256=meta['sourceSha256'],
                   JAVA_TOOL_OPTIONS=r'-Djdk.net.unixdomain.tmpdir=Z:\disable-af-unix')
        if args.action == 'write-check':
            env['MY12306_SEAT_WRITE_ONLY'] = 'true'
        maven = shutil.which('mvn.cmd') or shutil.which('mvn')
        if not maven:
            raise RuntimeError('Maven not found')
        subprocess.run([maven, '-q', '-pl', 'services/ticket-services', '-am', 'test',
                        '-Dtest=SeatAllocatorBenchmark', '-Dsurefire.failIfNoSpecifiedTests=false'],
                       cwd=ROOT / '12306/my12306', env=env, check=True)
        if snapshot(db) != meta['businessBefore']:
            raise RuntimeError('Benchmark altered business data')
        print('Isolated experiment complete; business snapshot unchanged', flush=True)
    elif args.action == 'apply-index':
        result = json.loads((OUT / 'benchmark.json').read_text('utf-8'))
        if not result.get('passed'):
            raise RuntimeError('Isolated experiment has not passed')
        apply_index(db)
    else:
        meta = json.loads((OUT / 'source-metadata.json').read_text('utf-8'))
        after = snapshot(db)
        if after != meta['businessBefore']:
            raise RuntimeError('Business snapshot changed')
        counts = db.sql(f"SELECT COUNT(*),SUM(train_id=1),SUM(train_id=1 AND seat_type=2 AND start_station='北京南' AND end_station='宁波' AND seat_status=0 AND del_flag=0) FROM {BENCH_DB}.t_seat;")
        if counts != [['27480', '9600', '810']]:
            raise RuntimeError('Benchmark inventory changed')
        rows = db.sql(f"SELECT id,train_id,carriage_number,seat_number,seat_type,start_station,end_station,price,seat_status,DATE_FORMAT(create_time,'%Y-%m-%d %H:%i:%s'),DATE_FORMAT(update_time,'%Y-%m-%d %H:%i:%s'),del_flag FROM {BENCH_DB}.t_seat ORDER BY id;")
        normalized = [[None if value == 'NULL' else value for value in row] for row in rows]
        fingerprint = hashlib.sha256(json.dumps(normalized, ensure_ascii=False,
            separators=(',', ':')).encode()).hexdigest()
        measured = json.loads((OUT / 'benchmark.json').read_text('utf-8'))
        if fingerprint != measured['dataFingerprint']:
            raise RuntimeError('Full benchmark data fingerprint changed')
        (OUT / 'final-verification.json').write_text(json.dumps(dict(businessUnchanged=True,
            businessAfter=after, benchCounts=counts, benchFingerprint=fingerprint), indent=2), encoding='utf-8')
        print('Business snapshots match; benchmark rows/train1/available:', counts, flush=True)
