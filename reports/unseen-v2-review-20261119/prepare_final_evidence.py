"""Preserve original bytes, reconstruct verified initial source, then normalize only reviewer working text to LF."""
import hashlib
import json
import pathlib
import zipfile
from datetime import datetime, timezone

ROOT=pathlib.Path('C:/Users/xunqian/Desktop/agent')
OUT=ROOT/'reports/unseen-v2-review-20261119'
AUTHOR=ROOT/'reports/unseen-v2-authoring-20261119'
SNAP=AUTHOR/'pre-maxrows200-original'
sha=lambda b:hashlib.sha256(b).hexdigest()

initial_hashes={
 'T1_single_table_basic.yaml':'ffde072a8e1c81dce666fa0bf7011bbf94100f01231376f3eae7cdb483a76fa4',
 'T2_single_table_agg.yaml':'25be00f444c2cc4a993b33dc669462b7d8949ca9b00c638b4e1eb9de4de57b2c',
 'T3_time_window.yaml':'7bca799f56eb5e34069f229e4adcb307128b37a68901695a1673c3d7368d5e12',
 'T4_multi_join.yaml':'ab074adebfd38d973927eecc99f71bc15ae52e95d887f3523c9893bc27c1465a',
 'T5_multi_join_deep.yaml':'5356465071e31d139dfba79cfb5028ddf81c0f66451caff65791972f5279094d',
 'T6_business_semantics.yaml':'21c84b0b451953d5db2a2327663acb63e01f2e072b957a357f6ed11a775e76ac'}

def prepare():
    archive=OUT/'pre-freeze-original-bytes.zip'
    assert not archive.exists(),'Never overwrite original archive'
    preserved={}
    with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for p in sorted(SNAP.rglob('*')):
            if p.is_file():
                key='author-pre-maxrows200/'+str(p.relative_to(SNAP)).replace('\\','/')
                b=p.read_bytes();z.writestr(key,b);preserved[key]=sha(b)
        for p in sorted(OUT.iterdir()):
            if p.is_file() and p.suffix in ('.json','.jsonl','.md','.sql','.py'):
                key='review-pre-LF/'+p.name
                b=p.read_bytes();z.writestr(key,b);preserved[key]=sha(b)
        for name,expected in initial_hashes.items():
            b=(SNAP/name).read_bytes()
            if name=='T3_time_window.yaml':
                items=json.loads(b)
                item=next(v for v in items if v['id']=='B2-T3-009')
                item['question']='查找与2018年6月这个自然月有至少一天重叠的活动，活动的起止日都必须已知且起止日视为包含当天。只输出活动ID、名称、开始日、结束日，按开始日、活动ID升序；覆盖全部状态，不补缺失日期。'
                item['gold_sql']=item['gold_sql'].replace(' AND start_date<=end_date','')
                item['notes']='重叠条件为start<下月初且end>=本月初；数据中活动总数小于500，返回完整集合。'
                b=(json.dumps(items,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
            assert sha(b)==expected,(name,sha(b),expected)
            key='initial-author-corpus-verified-reconstruction/'+name
            z.writestr(key,b);preserved[key]=sha(b)
        provenance={'format':'unseen-v2-original-byte-preservation-v1','at':datetime.now(timezone.utc).isoformat(),
         'note':'Author pre-row-cap snapshot and reviewer pre-LF working evidence preserved as raw bytes. Initial six-file source reconstructed from author pre-row-cap snapshot plus the original three changed fields of one item; every reconstructed file matches the initial author seal SHA-256 read before feedback.',
         'entriesSha256':preserved,'initialAuthorSealFileHashes':initial_hashes}
        z.writestr('archive-provenance.json',(json.dumps(provenance,ensure_ascii=False,indent=2)+'\n').encode())
    (OUT/'archive-provenance.json').write_text(json.dumps({**provenance,'archiveSha256':sha(archive.read_bytes())},ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    changed=[]
    for p in OUT.iterdir():
        if p.is_file() and p.suffix in ('.json','.jsonl','.md','.sql','.py'):
            before=p.read_bytes();after=before.replace(b'\r\n',b'\n').replace(b'\r',b'\n')
            if before!=after:
                p.write_bytes(after);changed.append(p.name)
    print(json.dumps({'archiveSha256':sha(archive.read_bytes()),'normalizedReviewerFiles':len(changed),'verifiedInitialFiles':6}))

if __name__=='__main__':prepare()
