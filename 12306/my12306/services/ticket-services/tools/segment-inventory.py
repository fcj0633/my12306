"""Offline migration/check wrapper. Reads the existing configuration; never prints credentials.

No automatic migration on application startup. Mutating modes require --writers-stopped.
"""
import argparse
import os
from pathlib import Path
import shutil
import socket
import subprocess
import yaml

MODULE=Path(__file__).resolve().parents[1]


class RedisConnection:
    def __init__(self,config):
        self.socket=socket.create_connection((config.get('host','127.0.0.1'),int(config.get('port',6379))),timeout=10)
        self.stream=self.socket.makefile('rb')
        if config.get('password'):
            self.command('AUTH',str(config['password']))
        if config.get('database'):
            self.command('SELECT',str(config['database']))

    def command(self,*args):
        parts=[str(x).encode('utf-8') for x in args]
        self.socket.sendall(b'*'+str(len(parts)).encode()+b'\r\n'+b''.join(b'$'+str(len(p)).encode()+b'\r\n'+p+b'\r\n' for p in parts))
        return self.read()

    def read(self):
        line=self.stream.readline()
        if not line:
            raise ConnectionError('Redis connection closed')
        marker,value=line[:1],line[1:-2]
        if marker==b'-':
            # Avoid echoing server messages that might include authentication arguments.
            raise RuntimeError('Redis rejected the cache maintenance operation')
        if marker==b'+':return value.decode('utf-8')
        if marker==b':':return int(value)
        if marker==b'$':
            length=int(value)
            if length==-1:return None
            payload=self.stream.read(length);self.stream.read(2);return payload.decode('utf-8')
        if marker==b'*':return [self.read() for _ in range(int(value))]
        raise ValueError('Invalid Redis response')

    def close(self):
        self.stream.close();self.socket.close()


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('mode',choices=['check','migrate','verify','rebuild-legacy','clear-caches'])
    parser.add_argument('--writers-stopped',action='store_true')
    parser.add_argument('--report',type=Path,default=MODULE/'target/segment-migration-report.json')
    args=parser.parse_args()
    if args.mode!='check' and not args.writers_stopped:
        parser.error('Stop ticket purchase/callback/recovery writers and pass --writers-stopped')
    config=yaml.safe_load((MODULE/'src/main/resources/application.yml').read_text(encoding='utf-8'))
    if args.mode=='clear-caches':
        connection=RedisConnection(config['spring']['data']['redis'])
        removed=0
        try:
            for prefix in ['my12306-ticket-service:train_station_remaining_ticket:',
                           'my12306-ticket-service:train_station_token_bucket:']:
                # Repeat SCAN until a full pass deletes nothing; deletion can change scan buckets.
                while True:
                    cursor='0';pass_removed=0
                    while True:
                        cursor,keys=connection.command('SCAN',cursor,'MATCH',prefix+'*','COUNT','200')
                        if any(not key.startswith(prefix) for key in keys):raise RuntimeError('Unexpected cache key')
                        if keys:pass_removed+=connection.command('DEL',*keys)
                        if cursor=='0':break
                    removed+=pass_removed
                    if not pass_removed:break
            print('Inventory cache keys deleted: '+str(removed))
        finally:connection.close()
        return
    env=os.environ.copy();source=config['spring']['datasource']
    env['TICKET_DB_URL']=env.get('TICKET_DB_URL',source['url'])
    env['TICKET_DB_USER']=env.get('TICKET_DB_USER',source['username'])
    env['TICKET_DB_PASSWORD']=env.get('TICKET_DB_PASSWORD',str(source['password']))
    if os.name=='nt':env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' -Djdk.net.unixdomain.tmpdir=Z:/disable-af-unix'
    args.report.parent.mkdir(parents=True,exist_ok=True)
    cp=MODULE/'target/segment-classpath.txt'
    subprocess.run([shutil.which('mvn'),'-q','compile','dependency:build-classpath','-Dmdep.outputFile=target/segment-classpath.txt'],cwd=MODULE,env=env,check=True)
    command=[shutil.which('java'),'-cp',str(MODULE/'target/classes')+os.pathsep+cp.read_text().strip(),
             'edu.swu.fcj.my12306.biz.ticketservice.toolkit.InventoryMigrationTool',args.mode,'--report='+str(args.report.resolve())]
    if args.writers_stopped:command.append('--writers-stopped')
    subprocess.run(command,cwd=MODULE,env=env,check=True)


if __name__=='__main__':main()
