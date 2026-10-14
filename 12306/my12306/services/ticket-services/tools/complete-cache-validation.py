"""Sequential final validation; avoid concurrent benchmarks on the same machine."""
import argparse, ctypes, json, os, subprocess, sys, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[5]
TOOLS=Path(__file__).resolve().parent
ART=TOOLS.parent/'target/cache-ab'
DOCS=ROOT/'docs/12-cache-optimization/evidence'

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--wait-pid',type=int);args=parser.parse_args()
    if args.wait_pid:
        kernel=ctypes.WinDLL('kernel32',use_last_error=True)
        kernel.OpenProcess.restype=ctypes.c_void_p
        kernel.WaitForSingleObject.argtypes=[ctypes.c_void_p,ctypes.c_ulong]
        kernel.CloseHandle.argtypes=[ctypes.c_void_p]
        handle=kernel.OpenProcess(0x100000,False,args.wait_pid)
        if handle:
            print('Waiting for preceding benchmark process',flush=True)
            try:
                while kernel.WaitForSingleObject(handle,1000)==258:pass
            finally:kernel.CloseHandle(handle)
        state=json.loads((ART/'current-run.json').read_text())
        if len(state['completed'])!=10:raise RuntimeError('Preceding five-round benchmark did not finish')
    stages=[
      ('hot100',['cache-ab.py','matrix','--rounds','3','--concurrency','100','--scenarios','hot-http']),
      ('functional',['cache-ab.py','matrix','--rounds','1','--scenarios','burst-city,burst-remaining,burst-cold,parallel-proof,expiry,pulses']),
      ('checks',['cache-ab.py','matrix','--models','B','--rounds','1','--scenarios','checks']),
      ('cost-probes',['cache-ab.py','matrix','--rounds','1','--seconds','5','--scenarios','unique-train,mixed,overlap-mixed']),
      ('regression',['validate-segment-inventory.py']),
      ('report',['summarize-cache-ab.py'])]
    for name,arguments in stages:
        print('Starting '+name,flush=True)
        with (ART/('final-'+name+'.log')).open('w',encoding='utf-8') as log:
            result=subprocess.run([sys.executable,str(TOOLS/arguments[0]),*arguments[1:]],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
        if result.returncode:raise RuntimeError(name+' failed; inspect its log')
        print('Completed '+name,flush=True)
    print('Final validation sequence complete',flush=True)
if __name__=='__main__':main()
