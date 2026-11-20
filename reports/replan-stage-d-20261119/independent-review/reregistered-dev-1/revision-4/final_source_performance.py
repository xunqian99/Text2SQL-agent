"""One final-source performance sample at each scale; no key, network or Java."""
from __future__ import annotations
import json,socket,subprocess,sys
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[5];OUT=Path(__file__).resolve().parent
sys.dont_write_bytecode=True;sys.path.insert(0,str(ROOT/'scripts'))
import benchmark_replan_quota_monitor as b
import replan_quota_monitor as q
import run_replan_stage_d_reregistered as n
HELPER='35b86ea00752c4e4a986480c8be266e87345c0617bd041214cbf17f53e7cb9db'
DRIVER='bda005614aa90c7f872f9451d0654a958204b2d4f5f807038d999245892d1d4e'
if __name__=='__main__':
    assert n.sha(Path(q.__file__))==HELPER and n.sha(Path(n.__file__))==DRIVER
    target=OUT/'final-source-performance';target.mkdir()
    results=[]
    with patch.object(b,'OUTPUT',target),patch.object(n,'_old_real_key',side_effect=AssertionError('real key forbidden')),\
         patch.object(socket.socket,'connect',side_effect=AssertionError('network forbidden')),\
         patch.object(subprocess,'Popen',side_effect=AssertionError('Java/child process forbidden')):
        for size in (102,902):
            with patch.object(n.d,'BATCH',target/str(size)):
                results.append(b.measure(b.build(size)))
    assert n.sha(Path(q.__file__))==HELPER and n.sha(Path(n.__file__))==DRIVER
    with (OUT/'final-source-performance.json').open('x',encoding='utf-8') as f:json.dump(dict(
        results=results,sourceHelperSha256=HELPER,sourceDriverSha256=DRIVER,benchmarkSha256=n.sha(Path(b.__file__)),
        oneSamplePerScale=True,oldPerformance7NotRelabeled=True,osCacheControlled=False,actualP95=False,
        sourceStorageShape='Synthetic cumulative two calls per question, representative terminal shape, not average actual payload or P95',
        providerCalls=0,keyReads=0,javaProcesses=0,actualBatchChanged=False),f,ensure_ascii=False,indent=2)
