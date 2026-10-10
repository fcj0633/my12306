"""Alternate saved baseline/candidate artifacts with the same live injector."""
import argparse,importlib.util,json,os,pathlib,shutil,subprocess,time
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('warm',HERE/'warm-runner.py');w=importlib.util.module_from_spec(spec);spec.loader.exec_module(w)
OUT=w.OUT;pwsh=shutil.which('pwsh') or 'powershell.exe'
current=None
def deploy(version,label):
    global current
    script=str(HERE/'warm-ticket.ps1')
    subprocess.run([pwsh,'-NoProfile','-File',script,'-Action','stop','-OutputDirectory',str(OUT),'-Label','before-'+label],check=True)
    subprocess.run([pwsh,'-NoProfile','-File',script,'-Action','start','-OutputDirectory',str(OUT),'-Label',label,
                    '-JarPath',str(OUT/version/'ticket-services-0.0.1-SNAPSHOT.jar')],check=True)
    current=version;w.c.base.refresh();stabilize(label)
def stabilize(label):
    for attempt in range(3):
        try:w.warm(label+f'-attempt{attempt}');return
        except RuntimeError as error:
            if 'Warmup not stable' not in str(error):raise
            print('Exploratory warmup group did not stabilize; exclude and inspect next group',flush=True)
            if attempt==2:raise
            w.guard()
def main(resume=False,new_series=False,series_id=0):
    global current
    excluded=[];selected=[];series=series_id or (1 if new_series else 0)
    os.environ['WARM_RUN_PREFIX']=f'matrix{series}-'
    if resume:
        current='candidate';w.c.base.refresh();stabilize(f'candidate-recovery-{series}')
    else:deploy('candidate',f'candidate-discovery-{series}')
    w.recalibrate(f'candidate-discovery-{series}')
    window=json.loads((OUT/'calibration.json').read_text('utf-8'))['seconds']
    old=json.loads((OUT/'baseline-0-block.json').read_text('utf-8'))
    reusable=not new_series and all(row['window']==window for row in old)
    if not reusable:excluded.extend(dict(row,excludedReason='common window changed after candidate calibration') for row in old)
    while True:
        os.environ['WARM_RUN_PREFIX']=f'matrix{series}-'
        selected=old.copy() if reusable else []
        restart=False
        for index in range(3):
            for version in ['baseline','candidate']:
                if reusable and index==0 and version=='baseline':continue
                if current!=version:deploy(version,f'{version}-{series}-{index}')
                try:w.block(version,index)
                except AssertionError:
                    attempted=[json.loads(path.read_text('utf-8')) for path in OUT.glob(f'matrix{series}-{version}-{index}-*-result.json')]
                    if not attempted or not any(row['capReached'] for row in attempted):raise
                    excluded.extend(dict(row,excludedReason='request protection; entire comparison series excluded') for row in selected+attempted)
                    restart=True;break
                rows=json.loads((OUT/f'{version}-{index}-block.json').read_text('utf-8'));selected.extend(rows)
                w.dump(OUT/'comparison-in-progress.json',selected)
            if restart:break
        w.dump(OUT/'comparison-excluded.json',excluded)
        if not restart:
            assert len(selected)==36 and len({row['window'] for row in selected})==1
            w.dump(OUT/'comparison-matrix.json',selected);return
        config=json.loads((OUT/'calibration.json').read_text('utf-8'));config['seconds']-=.25
        if config['seconds']<.25:raise RuntimeError('810 inventory insufficient at minimum window')
        w.dump(OUT/'calibration.json',config);reusable=False;series+=1
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--resume-candidate',action='store_true');parser.add_argument('--new-series',action='store_true');parser.add_argument('--series',type=int,default=0);a=parser.parse_args();main(a.resume_candidate,a.new_series,a.series)
