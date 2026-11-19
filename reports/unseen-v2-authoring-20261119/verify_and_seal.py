from pathlib import Path
import json,hashlib,datetime,re

root=Path(r'C:\Users\xunqian\Desktop\agent')
report=root/'reports/unseen-v2-authoring-20261119'
paths=sorted((root/'data/blind/v2-unseen-20261119').glob('T*.yaml'))
assert len(paths)==6
all_cases=[]
quota={}
for path in paths:
    b=path.read_bytes()
    assert not b.startswith(b'\xef\xbb\xbf') and b'\r' not in b
    group=json.loads(b.decode('utf-8'))
    assert isinstance(group,list)
    for c in group:
        assert set(c)=={'id','difficulty','tags','question','gold_sql','notes'}
        assert all(isinstance(c[k],str) and c[k] for k in ['id','difficulty','question','gold_sql','notes'])
        assert isinstance(c['tags'],list) and c['tags'] and all(isinstance(t,str) for t in c['tags'])
        assert re.match(r'^(SELECT|WITH)\b',c['gold_sql'],re.I)
        assert not re.search(r'\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|COPY|GRANT|REVOKE)\b',c['gold_sql'],re.I)
    quota[group[0]['difficulty']]=len(group)
    all_cases.extend(group)
assert len(all_cases)==100
assert len({x['id'] for x in all_cases})==100
assert list(quota.values())==[20,20,16,22,10,12]
dry=json.loads((report/'gold-dry-run-results.json').read_text(encoding='utf-8'))['cases']
assert {x['id'] for x in dry}=={x['id'] for x in all_cases}
assert all(x['status']=='PASS' and 0<x['rowCount']<=200 and x['executionRole']=='text2sql_ro' and x['readOnly'] for x in dry)
dup=json.loads((report/'duplicate-check.json').read_text(encoding='utf-8'))
assert all(dup[k]==0 for k in ['oldParseErrors','idOverlapCount','normalizedQuestionOverlapCount','newDuplicateIds','newDuplicateQuestions'])
manifest=json.loads((report/'authoring-manifest.json').read_text(encoding='utf-8'))
sha=lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
assert all(sha(root/p)==v for p,v in manifest['files'].items())
assert manifest['businessTableCoverageCount']==37
seal=dict(format='unseen-v2-author-seal-v1',sealedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),total=100,quota=quota,finalGoldErrors=0,finalGoldPasses=100,maxRowsAllowed=200,over200=0,over500=0,emptyResults=0,exactQuestionOverlap=0,idOverlap=0,providerCalls=0,corpusSha256=manifest['corpusSha256'],yamlSha256=manifest['files'],manifestSha256=sha(report/'authoring-manifest.json'),dryRunResultsSha256=sha(report/'gold-dry-run-results.json'),duplicateCheckSha256=sha(report/'duplicate-check.json'),independentReviewerCompleted=False)
(report/'seal.json').write_text(json.dumps(seal,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(seal,ensure_ascii=False))
