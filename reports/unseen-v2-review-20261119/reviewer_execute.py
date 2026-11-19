"""Independent read-only Gold execution. No production or prior case sources read."""
import csv
import hashlib
import io
import json
import pathlib
import subprocess
import sys
import time
from datetime import datetime, timezone

ROOT = pathlib.Path('C:/Users/xunqian/Desktop/agent')
SUITE = ROOT / 'data/blind/v2-unseen-20261119'
OUT = ROOT / 'reports/unseen-v2-review-20261119'
CMD = ['docker', 'exec', '-i', 'text2sql-postgres', 'psql', '-X', '-qAt',
       '-v', 'ON_ERROR_STOP=1', '-U', 'text2sql_ro', '-d', 'olist']

def execute(sql):
    script = "BEGIN READ ONLY;\nSET LOCAL statement_timeout='8s';\n" + sql + '\nROLLBACK;\n'
    began = time.perf_counter()
    p = subprocess.run(CMD, input=script, text=True, encoding='utf-8',
                       capture_output=True, timeout=20)
    return {'ok': p.returncode == 0, 'stdout': p.stdout.strip(), 'stderr': p.stderr.strip(),
            'wallMs': round((time.perf_counter()-began)*1000, 2)}

def load_items():
    items = []
    for path in sorted(SUITE.glob('*.yaml')):
        raw = path.read_bytes()
        for item in json.loads(raw):
            item['sourceFile'] = path.name
            item['sourceSha256'] = hashlib.sha256(raw).hexdigest()
            items.append(item)
    return items

if __name__ == '__main__':
    out_name = sys.argv[1] if len(sys.argv)>1 else 'initial-execution-results.json'
    ids = set(sys.argv[2:])
    items = [i for i in load_items() if not ids or i['id'] in ids]
    identity = execute("SELECT json_build_object('user',current_user,'readOnly',current_setting('transaction_read_only'),'timeout',current_setting('statement_timeout')); ")
    rows = []
    for item in items:
        sql = item['gold_sql'].rstrip().rstrip(';')
        result = execute("SELECT json_build_object('rows',COALESCE(json_agg(q),'[]'::json)) FROM (" + sql + ') q;')
        record = {k:item[k] for k in ('id','sourceFile','sourceSha256')}
        record.update({'sqlSha256':hashlib.sha256(item['gold_sql'].encode()).hexdigest(), **result})
        if result['ok']:
            parsed = json.loads(result['stdout'])['rows']
            record['resultRowCount'] = len(parsed)
            record['rows'] = parsed
            record['resultSha256'] = hashlib.sha256(json.dumps(parsed,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
            record.pop('stdout')
        rows.append(record)
    summary = {'format':'unseen-v2-independent-gold-execution-v1','at':datetime.now(timezone.utc).isoformat(),
               'identity':identity,'count':len(rows),'executionPasses':sum(x['ok'] for x in rows),
               'over500':sum(x.get('resultRowCount',0)>500 for x in rows),
               'emptyResults':sum(x.get('resultRowCount',-1)==0 for x in rows),
               'maxRows':max((x.get('resultRowCount',0) for x in rows),default=0),
               'wallMs':round(sum(x['wallMs'] for x in rows),2),'results':rows}
    (OUT/out_name).write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in summary.items() if k not in ('results','identity')},ensure_ascii=False))
