import hashlib, io, json, math, pathlib, subprocess, zipfile, xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[4]
OUT = pathlib.Path(__file__).resolve().parent
PACK = ROOT / 'reports/replan-stage-c-20261119/c1-submission-1'
BASE = ROOT / 'reports/replan-stage-b-20261119/attempt-5'
ROWS = []
def sha(b): return hashlib.sha256(b).hexdigest()
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def check(group, ident, passed, detail=None):
    ROWS.append(dict(group=group,id=ident,passed=bool(passed),detail=detail))
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT)
def blobs(commit, paths):
    raw = subprocess.check_output(['git','cat-file','--batch'], cwd=ROOT,
        input=b''.join((commit+':'+p+'\n').encode() for p in paths))
    stream=io.BytesIO(raw); result={}
    for p in paths:
        header=stream.readline().decode().strip().split()
        if header[-1]=='missing': result[p]=None;continue
        size=int(header[-1]);result[p]=stream.read(size);assert stream.read(1)==b'\n'
    return result
def units(s): return len(s.encode('utf-16-le'))//2
def stats(vals): return dict(items=len(vals),totalChars=sum(vals),p95Chars=sorted(vals)[math.ceil(.95*len(vals))-1])

head=git('rev-parse','HEAD').decode().strip()
manifest_path=PACK/'submission-1-manifest.json'
check('identity','manifest-sha',sha(manifest_path.read_bytes())=='dfc4f67ec1c29bfb5f86d454f34c1caa8fa13d01734d82bce880109064e101ad')
manifest=read(manifest_path)['files']
committed=blobs(head,list(manifest))
original=blobs('c550cb6692a8f7a1be4c27a1ff513607969ffc5d',list(manifest))
archival_original=[]
for p,h in manifest.items():
    check('manifest-local',p, (ROOT/p).exists() and sha((ROOT/p).read_bytes())==h)
    check('manifest-committed',p,committed[p] is not None and sha(committed[p])==h)
    if original[p] is None or sha(original[p])!=h: archival_original.append(p)
identity=read(PACK/'candidate-identity.json')
for group,key in [('source-worktree','sourceFiles'),('compiled-worktree','compiledClasses')]:
    for p,h in identity[key].items(): check(group,p,(ROOT/p).exists() and sha((ROOT/p).read_bytes())==h)
source_committed=blobs('e114648',list(identity['sourceFiles']))
for p,h in identity['sourceFiles'].items(): check('source-committed',p,source_committed[p] is not None and sha(source_committed[p])==h)
changed=git('diff','--name-only','37017182d67a1fba7d16b399cc616565a784aac0','e114648','--','src/main','pom.xml').decode().splitlines()
check('scope','production-only-PromptTemplate',changed==['src/main/java/com/text2sql/agent/generation/PromptTemplate.java'],changed)
jar=pathlib.Path(identity['savedJar'])
check('identity','jar-sha',sha(jar.read_bytes())==identity['jarSha256']=='51874452c5c0acad14a733fc6d0fbec06c81273aaabf7b16eabc3b4b4653c2c2')
with zipfile.ZipFile(jar) as z:
    for p,h in identity['dependencies'].items():check('jar-dependencies',p,sha(z.read(p))==h)
    for p,h in identity['compiledClasses'].items():
        entry='BOOT-INF/classes/'+p.removeprefix('target/classes/')
        check('jar-classes',p,entry in z.namelist() and sha(z.read(entry))==h)

principle='【使用原则】当用户问题涉及以下指标且未指定特殊计算口径时，遵循标准度量的表达式与过滤条件；若用户明确指明了特定的计算口径或聚合逻辑，以用户明确说明为准。\n'
comparison=[]; all_v4_base=[];all_v4_new=[]
for dataset in ['original200','development100','formal100']:
    bp=BASE/('messages-B-'+dataset+'.json');cp=PACK/('messages-C1-'+dataset+'.json')
    b=read(bp)['items'];c=read(cp)['items']
    check('capture',dataset+'-same-count',len(b)==len(c)==(200 if dataset=='original200' else 100))
    before=[];after=[];removals=0;unchanged=0
    for n,(old,new) in enumerate(zip(b,c)):
        ident=dataset+':'+str(n)+':'+new['id']
        check('capture-item',ident+'-context',all(old.get(k)==new.get(k) for k in ['retrievedTables','schemaDdlChars','id','question']))
        check('capture-item',ident+'-roles',[m['role'] for m in old['messages']]==[m['role'] for m in new['messages']]==['SYSTEM','USER'])
        ou=old['messages'][1]['text'];nu=new['messages'][1]['text']
        has='=== 业务指标口径约定（数仓标准度量）===\n' in ou
        check('capture-item',ident+'-user-exact',nu==(ou.replace(principle,'',1) if has else ou) and ou.count(principle)==int(has))
        check('capture-item',ident+'-system-global',new['messages'][0]['text']==c[0]['messages'][0]['text'])
        check('capture-item',ident+'-no-replacement',all(chr(65533) not in m['text'] for m in new['messages']))
        before.append(sum(units(m['text']) for m in old['messages']));after.append(sum(units(m['text']) for m in new['messages']))
        removals+=has;unchanged+=ou==nu
    comparison.append(dict(dataset=dataset,baseline=stats(before),candidate=stats(after),removedPrinciples=removals,unchangedUsers=unchanged))
    if dataset!='original200':all_v4_base+=before;all_v4_new+=after
    if dataset=='original200':
        pre=read(ROOT/'reports/replan-stage-c-20261119/preregistration.json')
        check('admission','baseline-capture-sha',sha(bp.read_bytes())==pre['baseOriginal200CaptureSha256'])
        check('admission','reduction-at-least20',sum(after)<=.8*sum(before))
        check('admission','p95-not-rise',stats(after)['p95Chars']<=stats(before)['p95Chars'])
        check('admission','registered-total-and-p95',stats(before)['totalChars']==1657683 and stats(before)['p95Chars']==10101)

suites=list((PACK/'full-build-final/surefire').glob('TEST-*.xml'));totals={k:0 for k in ['tests','failures','errors','skipped']}
for p in suites:
    r=ET.parse(p).getroot()
    for k in totals:totals[k]+=int(r.attrib[k])
check('engineering','100-suites-1480-zero',len(suites)==100 and totals==dict(tests=1480,failures=0,errors=0,skipped=0),totals)
for n in ['original200','development100','formal100']:
    d=read(next((PACK/('gold-'+n+'-evidence')).glob('eval-*-dryrun.json')))
    check('gold-archive',n+'-exists',bool(d))
for n in ['access-http-evidence','catalog-http-evidence','cost-http-evidence','session-http-evidence']:
    d=read(PACK/n/'summary.json')
    check('http-archive',n+'-exists',bool(d))
out=dict(head=head,sourceCommit='e114648',rows=ROWS,total=len(ROWS),passed=sum(r['passed'] for r in ROWS),failed=sum(not r['passed'] for r in ROWS),
    manifestEntries=len(manifest),originalDeliveryArchivalMissingOrMismatch=archival_original,comparisons=comparison,v4Combined=dict(baseline=stats(all_v4_base),candidate=stats(all_v4_new)),
    engineering=dict(suites=len(suites),**totals),providerCalls=0,databaseConnections=0,
    limitations=['Archive existence checks for Gold/HTTP do not independently repeat their execution.','Initial c550cb6 archival omissions retained separately; current HEAD commit bytes checked.'])
(OUT/'evidence-results-first.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in out.items() if k not in ['rows']},ensure_ascii=True,indent=2))
raise SystemExit(int(out['failed']>0))
