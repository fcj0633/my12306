"""Remove only prefixes recorded by this task's benchmark logs, using Redis RESP (no extra package)."""
import argparse, re, socket
from pathlib import Path
import yaml

MODULE=Path(__file__).resolve().parents[1]

class Redis:
    def __init__(self,config):
        self.socket=socket.create_connection((config['host'],int(config.get('port',6379))),timeout=5)
        self.stream=self.socket.makefile('rb')
        if config.get('password'):self.command('AUTH',str(config['password']))
    def read(self):
        line=self.stream.readline();kind=line[:1];data=line[1:-2]
        if kind==b'+':return data
        if kind==b'-':raise RuntimeError('Redis rejected the cleanup operation')
        if kind==b':':return int(data)
        if kind==b'$':
            length=int(data)
            if length==-1:return None
            value=self.stream.read(length);self.stream.read(2);return value
        if kind==b'*':return [self.read() for _ in range(int(data))]
        raise RuntimeError('Unexpected Redis protocol response')
    def command(self,*args):
        values=[x if isinstance(x,bytes) else str(x).encode('utf-8') for x in args]
        self.socket.sendall(b'*'+str(len(values)).encode()+b'\r\n'+b''.join(b'$'+str(len(v)).encode()+b'\r\n'+v+b'\r\n' for v in values))
        return self.read()
    def close(self):self.stream.close();self.socket.close()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    config=yaml.safe_load((MODULE/'src/main/resources/application.yml').read_text(encoding='utf-8'))['spring']['data']['redis']
    prefixes=[]
    for log in (MODULE/'target/cache-ab').glob('*.log'):
        match=re.fullmatch(r'((?:matrix|proof)_[0-9]+)-([AB])',log.stem)
        if match:prefixes.append('codex:cache-ab:'+match[1]+'_'+match[2]+':')
    connection=Redis(config);found=deleted=0
    try:
        for prefix in prefixes:
            cursor=b'0'
            while True:
                cursor,keys=connection.command('SCAN',cursor,'MATCH',prefix+'*','COUNT',200)
                if any(not key.startswith(prefix.encode()) for key in keys):raise RuntimeError('Refusing an out-of-namespace key')
                found+=len(keys)
                if args.apply and keys:deleted+=connection.command('DEL',*keys)
                if cursor==b'0':break
    finally:connection.close()
    print('Recorded namespaces:',len(prefixes),'matched keys:',found,'removed keys:',deleted,'apply:',args.apply)

if __name__=='__main__':main()
