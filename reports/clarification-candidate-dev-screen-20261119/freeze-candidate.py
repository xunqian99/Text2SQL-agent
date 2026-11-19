import hashlib,json,pathlib,subprocess,datetime
root=pathlib.Path.cwd()
out=root/'reports/clarification-candidate-dev-screen-20261119'
commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
protected=['.gitattributes','src','pom.xml','data/eval','data/holdout','scripts/run_eval.ps1','scripts/run_stage2_regression.ps1','scripts/summarize_stage2_regression.py','scripts/summarize_request_budget.py','scripts/summarize_holdout.py','docs/CLARIFICATION_CANDIDATE_PROTOCOL_20261119.md']
files=subprocess.check_output(['git','ls-files','--',*protected],text=True).splitlines()
for name in files:
    expected=subprocess.check_output(['git','cat-file','--filters','--path='+name,commit+':'+name])
    actual=(root/name).read_bytes()
    if expected!=actual:
        # Only checkout newline normalization is allowed; source text must already equal the commit.
        if expected.replace(b'\r\n',b'\n')!=actual.replace(b'\r\n',b'\n'):
            raise RuntimeError('Uncommitted source differs: '+name)
        pass # Preserve checkout bytes after normalized-content comparison.
if subprocess.check_output(['git','status','--porcelain','--',*protected],text=True).strip():
    raise RuntimeError('Protected working tree dirty')
sha=lambda p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
hashes={name:sha(root/name) for name in files}
hashes['src/main/resources/application-local.yml']=sha(root/'src/main/resources/application-local.yml')
for script in ['target/run-clarification-candidate-screen.ps1','target/freeze_clarification_candidate.py']:
    hashes[script]=sha(root/script)
runtime={str(p.resolve()):sha(p) for base in ['target/classes','target/test-classes'] for p in sorted((root/base).rglob('*')) if p.is_file()}
cp='target/test-classes;target/classes;'+(root/'target/prompt-composition-classpath.txt').read_text(encoding='utf-8-sig').strip()
for entry in cp.split(';'):
    p=pathlib.Path(entry)
    if p.is_file():runtime[str(p.resolve())]=sha(p)
gold=list((out/'gold').glob('eval-*.json'))
assert len(gold)==1
g=json.loads(gold[0].read_text(encoding='utf-8-sig'))
assert g['overall']['correct']==200 and g['meta']['gitCommit']=='80d36673a71fa47739e526321e1e0f2b9291ccd0'
assert not any(i['calls'] for i in g['itemEvidence'])
for split in ['dev','eval']:
    capture=json.loads((out/f'prompt-capture-{split}.json').read_text(encoding='utf-8-sig'))
    assert capture['itemCount']==100 and not capture['sameProcessMismatches'] and capture['modelCallCount']==0
stamp=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
assert not(stamp.weekday()<5 and (9<=stamp.hour<12 or 14<=stamp.hour<18))
manifest=dict(format='clarification-candidate-dev-screen-v1',state='READY',frozenAt=stamp.isoformat(),notBeforeBeijing=stamp.isoformat(),codeCommit=commit,expectedPromptVersion='p19-request-scope-v19',outputDir='reports/clarification-candidate-dev-screen-20261119',protocolPath='docs/CLARIFICATION_CANDIDATE_PROTOCOL_20261119.md',classpath=cp,plannedRealRequests=100,screenThresholds=dict(correctAtLeast=71,deepAtLeast=4,businessAtLeast=6,timeoutsAtMost=9),protectedSha256=hashes,runtimeSha256=runtime,captureSha256={f'prompt-capture-{s}.json':sha(out/f'prompt-capture-{s}.json') for s in ['dev','eval']},pricingSource='https://api-docs.deepseek.com/zh-cn/quick_start/pricing/',pricingVerifiedBeijing=stamp.isoformat(),goldReport=str(gold[0].relative_to(out)),goldSha256=sha(gold[0]),javaTests=1118,toolTests=102,engineeringEvidenceReused=True,goldProductionCommit=g['meta']['gitCommit'])
(out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(out/'protocol-before-run.md').write_bytes((root/manifest['protocolPath']).read_bytes())
print('READY',commit,'protected=',len(hashes),'runtime=',len(runtime),'Beijing=',stamp.isoformat())
