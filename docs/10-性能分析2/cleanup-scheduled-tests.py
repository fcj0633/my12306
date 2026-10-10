"""Remove only this run's logged, already deleted orders from delayed close queues.

Run after owned services stop. Redisson 3.31.0 Bc0Lc0 packing and Kryo string
encoding are checked against queue contents; any unexpected encoding aborts.
No Redis key is deleted wholesale and no existing order's task is removed.
"""
import argparse, importlib.util, pathlib, re
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('current',HERE/'current-performance.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
p=argparse.ArgumentParser();p.add_argument('--output-dir');a=p.parse_args()
if a.output_dir:c.OUT=pathlib.Path(a.output_dir).resolve()
owned=set()
for name in sorted(set(['ticket.stdout.log','ticket.initial.stdout.log','ticket2.stdout.log']+[path.name for path in c.OUT.glob('ticket.*.stdout.log')])):
    if not (c.OUT/name).exists():continue
    for line in (c.OUT/name).read_text('utf-8',errors='replace').splitlines():
        if '购票锁已获取' in line and 'purchase_tickets_user_perf400_' in line:
            match=re.search(r'orderSn=(\d+)',line)
            if match:owned.add(match[1])
queue='my12306-order-service:delay-close-order-queue'
delayed='redisson_delay_queue:{'+queue+'}'
timeout='redisson_delay_queue_timeout:{'+queue+'}'
read="""
local result={}
for _,v in ipairs(redis.call('LRANGE',KEYS[1],0,-1)) do
 local _,value=struct.unpack('Bc0Lc0',v)
 result[#result+1]=(value:gsub('.',function(x)return string.format('%02x',string.byte(x)) end))
end
for _,value in ipairs(redis.call('LRANGE',KEYS[2],0,-1)) do
 result[#result+1]=(value:gsub('.',function(x)return string.format('%02x',string.byte(x)) end))
end
return result
"""
pending=c.base.redis('EVAL',read,2,delayed,queue)
candidates=set()
for encoded in pending:
    value=bytes.fromhex(encoded)
    assert value[0]==3 and 128<=value[-1]<=255,'Unexpected Kryo encoding; no mutation'
    sn=(value[1:-1]+bytes([value[-1]-128])).decode('ascii')
    assert re.fullmatch(r'\d{19}',sn),'Unexpected order number; no mutation'
    if sn in owned:candidates.add(sn)
existing=set()
ordered=sorted(candidates)
for i in range(0,len(ordered),200):
    quoted=','.join("'"+sn+"'" for sn in ordered[i:i+200])
    existing.update(r[0] for r in c.base.sql(f'SELECT order_sn FROM 12306_order.t_order WHERE order_sn IN ({quoted});'))
remove=sorted(candidates-existing)
lua="""
local wanted={}
for _,sn in ipairs(ARGV) do
 local value=string.char(3)..sn:sub(1,-2)..string.char(sn:byte(-1)+128)
 wanted[value]=true
end
local count=0
for _,v in ipairs(redis.call('LRANGE',KEYS[1],0,-1)) do
 local _,value=struct.unpack('Bc0Lc0',v)
 if wanted[value] then
  count=count+redis.call('LREM',KEYS[1],0,v)
  redis.call('ZREM',KEYS[2],v)
 end
end
for value,_ in pairs(wanted) do count=count+redis.call('LREM',KEYS[3],0,value) end
return count
"""
removed=0
for i in range(0,len(remove),200):removed+=int(c.base.redis('EVAL',lua,3,delayed,timeout,queue,*remove[i:i+200]))
remaining=c.base.redis('EVAL',read,2,delayed,queue)
removed_encoded={bytes([3])+sn[:-1].encode()+bytes([ord(sn[-1])+128]) for sn in remove}
assert not any(bytes.fromhex(v) in removed_encoded for v in remaining)
result=dict(loggedOwnedOrders=len(owned),pendingBefore=len(pending),
    removableOwnedDeletedOrders=len(remove),preservedExistingOrders=len(existing),
    removedEntries=removed,pendingAfter=len(remaining),remainingRemovedOwnedEntries=0)
c.dump(c.OUT/'scheduled-cleanup-verification.json',result)
print(result)
