"""Offline diagnostics after rejected preflight. Prints aggregates only, including for blind data."""
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
import run_stage2_v3_comparison as ctl

HERE=Path(__file__).resolve().parent
plan=ctl.read(HERE/'plan.json')
summaries=[]
for dataset,side in [('dev','C'),('formal','B'),('formal','C'),('blind','B'),('blind','C')]:
    output=HERE/f'diagnostic_gold_{dataset}_{side}'
    env=ctl.clean_environment()
    env['AGENT_LLM_API_KEY']='stage2-offline-only-not-real'
    command=ctl.build_command(plan,side,dataset,output,True)
    code=ctl.launch(command,plan['systems'][side],output,env)
    reports=list(output.glob('eval-*.json'))
    r=ctl.read(reports[0]) if len(reports)==1 else {}
    summary=dict(dataset=dataset,side=side,exitCode=code,total=r.get('overall',{}).get('total'),
        correct=r.get('overall',{}).get('correct'),providerCalls=sum(len(i['calls']) for i in r.get('itemEvidence',[])))
    try:
        ctl.analyze_output(output,True,plan['systems'][side],plan['datasets'][dataset])
        summary['auditPassed']=True
    except Exception as e:
        summary.update(auditPassed=False,errorType=type(e).__name__,errorSha256=ctl.digest(str(e)))
    summaries.append(summary)
    ctl.write(HERE/'gold-diagnostic-summary.json',summaries)
    print(json.dumps(summary),flush=True)
