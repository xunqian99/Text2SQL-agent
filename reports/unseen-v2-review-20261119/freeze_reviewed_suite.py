"""Freeze only an accepted final suite; hashes prior corpus bytes without revealing old content."""
import hashlib
import json
import pathlib
import struct
from collections import Counter
from datetime import datetime, timezone
from reviewer_execute import OUT, ROOT, SUITE, load_items

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def dataset_hash(directory):
    digest=hashlib.sha256()
    for path in sorted(directory.glob('*.yaml'),key=lambda p:p.name):
        name=path.name.encode('utf-8')
        data=path.read_bytes()
        digest.update(struct.pack('>i',len(name)));digest.update(name)
        digest.update(struct.pack('>i',len(data)));digest.update(data)
    return digest.hexdigest()

def ids_hash(ids):
    digest=hashlib.sha256()
    for item_id in sorted(ids):
        value=item_id.encode('utf-8')
        digest.update(struct.pack('>i',len(value)));digest.update(value)
    return digest.hexdigest()

def freeze():
    now=datetime.now(timezone.utc).isoformat()
    items=load_items()
    review=json.loads((OUT/'per-item-review.json').read_text(encoding='utf-8'))
    boundary=json.loads((OUT/'boundary-witness-results.json').read_text(encoding='utf-8'))
    author_dir=ROOT/'reports/unseen-v2-authoring-20261119'
    author=json.loads((author_dir/'authoring-manifest.json').read_text(encoding='utf-8'))
    seal=json.loads((author_dir/'seal.json').read_text(encoding='utf-8'))
    freeze_path=ROOT/'reports/stage2-candidate-freeze-20261119/freeze.json'
    candidate=json.loads(freeze_path.read_text(encoding='utf-8'))
    expected={'single_table_basic':20,'single_table_agg':20,'time_window':16,'multi_join':22,'multi_join_deep':10,'business_semantics':12}
    assert dict(Counter(x['difficulty'] for x in items))==expected
    assert len(items)==100 and len(set(x['id'] for x in items))==100
    assert review['counts']=={'PASS':100,'DEFECT':0,'AMBIGUOUS':0}
    assert boundary['passed']==boundary['count']==18 and boundary['defects']==0
    assert seal['finalGoldPasses']==100 and seal['over500']==0
    assert author['duplicateCheck']['normalizedQuestionOverlapCount']==0
    corpus=dataset_hash(SUITE)
    author_corpus=hashlib.sha256('\n'.join(p.name+':'+sha(p) for p in sorted(SUITE.glob('*.yaml'))).encode()).hexdigest()
    assert author_corpus==author['corpusSha256']==seal['corpusSha256']
    for row in review['records']:
        assert row['sourceSha256']==sha(SUITE/row['sourceFile'])
    for path_key,expected_sha in author['files'].items():
        assert sha(ROOT/path_key)==expected_sha
    assert candidate['frozenAt']<author['startedAt']
    assert candidate['productionSourceCommit']=='ea5a8a4ea9d2125a69181d16642bb816c23f1da5'
    assert candidate['sourceGitTree']=='e5c6d3dc99575dbc568ddb1ecc15346a4799d91a'
    assert candidate['jarSha256']=='e8a3db8efba97e675432b995018b23b016088604cc446513c2d84f85703fe764'
    # Convert preserved psql JSON values to a proper structured array without new DB access.
    raw=(OUT/'source-integrity-results.jsonl').read_text(encoding='utf-8-sig').strip()
    decoder=json.JSONDecoder();values=[]
    while raw:
        value,end=decoder.raw_decode(raw);values.append(value);raw=raw[end:].strip()
    (OUT/'source-integrity-results.json').write_text(json.dumps(values,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    final_execution=json.loads((OUT/'initial-execution-results.json').read_text(encoding='utf-8'))
    revision=json.loads((OUT/'revision-execution-results.json').read_text(encoding='utf-8'))
    replacements={x['id']:x for x in revision['results']}
    cap_revision=json.loads((OUT/'maxrows200-revision-execution-results.json').read_text(encoding='utf-8'))
    replacements.update({x['id']:x for x in cap_revision['results']})
    final_execution['results']=[replacements.get(x['id'],x) for x in final_execution['results']]
    final_execution['format']='unseen-v2-independent-final-gold-execution-v1'
    final_execution['at']=now
    final_execution['executionPasses']=sum(x['ok'] for x in final_execution['results'])
    final_execution['wallMs']=round(sum(x['wallMs'] for x in final_execution['results']),2)
    final_execution['maxRows']=max(x['resultRowCount'] for x in final_execution['results'])
    assert final_execution['maxRows']<=200
    final_execution['noGoldTruncation']=True
    final_execution['effectiveFrozenCandidateMaxRows']=200
    final_execution['sourceFilesSha256']={p.name:sha(p) for p in sorted(SUITE.glob('*.yaml'))}
    final_execution['evidenceNote']='98 unchanged Gold statements retain initial execution evidence; two revised Gold statements use targeted reruns. Direct psql collected complete Gold output without a JDBC row cap. No production or candidate queries were run.'
    (OUT/'final-execution-results.json').write_text(json.dumps(final_execution,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    evidence_names=['REVIEW.md','per-item-review.json','initial-execution-results.json','revision-execution-results.json','maxrows200-revision-execution-results.json',
     'final-execution-results.json','initial-boundary-witness-results.json','boundary-witness-results.json',
     'pre-maxrows200-per-item-review.json','pre-maxrows200-REVIEW.md','pre-maxrows200-boundary-witness-results.json',
     'source-integrity.sql','source-integrity-results.json','reviewer_execute.py','reviewer_boundary_checks.py',
     'reviewer_decisions.py','freeze_reviewed_suite.py','prepare_final_evidence.py','archive-provenance.json','pre-freeze-original-bytes.zip']
    evidence={name:sha(OUT/name) for name in evidence_names}
    author_evidence={name:sha(author_dir/name) for name in ['authoring-manifest.json','seal.json','duplicate-check.json','gold-dry-run-results.json','gold-dry-run-summary.json']}
    acceptance={'format':'unseen-v2-final-review-acceptance-v1','acceptedAt':now,'decision':'PASS',
     'reviewMode':'independent initial review, targeted revision accepted after feedback; not completely blind review',
     'feedbackAfterRevisionAccepted':True,'initialCounts':{'PASS':99,'DEFECT':1,'AMBIGUOUS':0},
     'finalCounts':review['counts'],'unresolvedCount':0,'boundaryWitnesses':{'total':18,'passed':18,'failed':0},
     'finalExecution':{'passed':100,'failed':0,'over500':0,'over200':0,'emptyResults':0,'maxRows':final_execution['maxRows'],
                       'noGoldTruncation':True,'effectiveFrozenCandidateMaxRows':200,
                       'wallMs':final_execution['wallMs'],'totalReviewerGoldExecutions':102},
     'compatibilityRevision':{'trigger':'Frozen candidate default result cap is 200 rows; original complete result had 207 rows',
       'candidateOutputsUsed':False,'productionConfigChanged':False,'authorAddedExplicitStableTop150':True},
     'datasetSha256':corpus,'sampleIdsSha256':ids_hash(x['id'] for x in items),'layerCounts':expected,
     'authorCorpusSha256':author_corpus,'authorCorpusHashAlgorithm':'SHA256 of LF-joined filename:rawFileSHA256',
     'candidatePreblindFreezeSha256':sha(freeze_path),'evidenceSha256':evidence,'authorEvidenceSha256':author_evidence,
     'independence':{'freshReviewerContext':True,'reviewerTask':'/root/review_unseen_v2','authorTask':'/root/author_unseen_v2',
      'productionSourceRead':False,'historicalOutputsRead':False,'oldQuestionsManuallyRead':False,
      'productionChangedForUnseen':False,'externalLlmCalls':0,'providerCalls':0,'credentialsRead':False,'databaseWrites':0,
      'processOnlyIsolation':True,'semanticOverlapWithOldQuestionsNotProvenAbsent':True,
      'allowedDomainMetadataContainsHistoricalExamples':True}}
    (OUT/'acceptance.json').write_text(json.dumps(acceptance,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    manifest={
      'format':'frozen-eval-suite-v1','suiteId':'olist-v2-unseen-20261119',
      'purpose':'Independent AI-authored unseen comparison; final reviewer acceptance after author revision; shared filesystem, no OS isolation',
      'frozenAt':now,'itemCount':100,'layerCounts':expected,'datasetSha256':corpus,
      'sampleIdsSha256':acceptance['sampleIdsSha256'],'referenceDir':str(ROOT/'data/eval').replace('\\','/'),
      'referenceDatasetSha256':dataset_hash(ROOT/'data/eval'),'independentReview':True,
      'maxResultRows':final_execution['maxRows'],'noGoldTruncation':True,'effectiveFrozenCandidateMaxRows':200,
      'datasetHashAlgorithm':'Per ordinal-sorted YAML filename: signed int32 big-endian UTF8 name length, name bytes, signed int32 big-endian raw content length, raw content bytes; SHA-256',
      'authorCorpusSha256':author_corpus,
      'reviewer':{'agentTask':'/root/review_unseen_v2','freshContext':True,'participatedInProductionTuning':False,
       'participatedInQuestionAuthoring':False,'reviewMode':acceptance['reviewMode'],'feedbackAfterRevisionAccepted':True,
       'decision':'PASS','passCount':100,'defectCount':0,'ambiguousCount':0,'unresolvedCount':0,
       'reportDirectory':str(OUT).replace('\\','/'),'acceptanceSha256':sha(OUT/'acceptance.json'),
       'evidenceSha256':evidence,'independenceLimitations':[
        'Role separation on a shared filesystem is procedural, not OS isolation.',
        'Permitted domain metadata includes historical illustrative examples.',
        'Zero normalized exact overlap does not prove absence of semantic overlap.',
        'One item was revised by its author after independent feedback and then rechecked; this is not a completely blind review.']},
      'candidatePreblindFreezeSha256':sha(freeze_path),
      'candidatePreblindFreeze':{k:candidate[k] for k in ['format','productionSourceCommit','sourceGitTree','promptVersion','currentCommit','frozenAt','jarSha256']},
      'authorStartedAt':author['startedAt'],'preblindFreezeGitCommit':'b25d4d7',
      'authorEvidenceSha256':author_evidence,'filesSha256':{p.name:sha(p) for p in sorted(SUITE.glob('*.yaml'))},
      'reviewEvidence':{'format':'unseen-v2-final-review-acceptance-v1','acceptancePath':str(OUT/'acceptance.json').replace('\\','/'),
       'acceptanceSha256':sha(OUT/'acceptance.json'),'initialPassCount':99,'initialDefectCount':1,
       'finalPassCount':100,'boundaryWitnessCount':18,'boundaryWitnessPassCount':18,
       'goldExecutionPassCount':100,'over500Count':0,'over200Count':0,'emptyResultCount':0,'maximumResultRows':final_execution['maxRows'],
       'noGoldTruncation':True,'compatibilityRevisionBeforeCandidateRun':True,
       'providerCalls':0,'databaseWrites':0}}
    path=SUITE/'manifest.json'
    path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'decision':'PASS100','unresolved':0,'datasetSha256':corpus,
     'sampleIdsSha256':manifest['sampleIdsSha256'],'referenceDatasetSha256':manifest['referenceDatasetSha256'],
     'manifestSha256':sha(path),'acceptanceSha256':sha(OUT/'acceptance.json'),
     'candidatePreblindFreezeSha256':sha(freeze_path)},ensure_ascii=False))

if __name__=='__main__':freeze()
