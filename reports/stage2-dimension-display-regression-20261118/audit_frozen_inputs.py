import json,hashlib,sys
from pathlib import Path
from datetime import datetime,timedelta,timezone
root=Path('reports/stage2-dimension-display-regression-20261118')
read=lambda p:json.loads(Path(p).read_text(encoding='utf-8-sig'))
cap=read('reports/dimension-display-dev-screen-20261118/prompt-capture-eval.json')
expected={i['id']:i for i in cap['items']}
manifest=read(root/'manifest.json')
not_before=datetime.fromisoformat(read('reports/dimension-display-dev-screen-20261118/manifest.json')['notBeforeBeijing'])
errors=[];rounds={}
for name in ('r1','r2','r3'):
 entry=next(x for x in manifest['runs'] if x['name']==name)
 report=read(root/name/entry['report'])
 matched=0
 for item in report['itemEvidence']:
  if item['calls']:
   ok=item['calls'][0]['promptSha256']==expected[item['id']]['promptSha256']
   if not ok: errors.append(name+'/'+item['id']+': prompt differs from pre-paid capture')
   matched+=ok
 ledger=[json.loads(line) for p in (root/name/'llm-calls').glob('*.jsonl') for line in p.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
 timestamps=[]
 for c in ledger:
  stamp=datetime.fromisoformat(c['timestamp'])
  if stamp.tzinfo is None:stamp=stamp.replace(tzinfo=timezone(timedelta(hours=8)))
  stamp=stamp.astimezone(timezone(timedelta(hours=8)))
  timestamps.append(stamp.isoformat())
  if stamp<not_before:errors.append(name+': before allowed start')
  for t in (stamp,stamp+timedelta(seconds=120)):
   if t.weekday()<5 and (9<=t.hour<12 or 14<=t.hour<18):errors.append(name+': peak/reserve violation')
 rounds[name]=dict(actualFirstPromptsMatched=matched,ledgerRows=len(ledger),firstTimestamp=min(timestamps),lastTimestamp=max(timestamps))
result=dict(format='p18-frozen-input-window-audit-v1',passed=not errors,errors=errors,rounds=rounds,modelCallsAdded=0)
(root/'frozen-input-window-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=True,indent=2))
sys.exit(0 if not errors else 1)
