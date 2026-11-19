"""Read only explicitly public inputs and archived runtime evidence; write this directory only."""
from __future__ import annotations
import csv
import hashlib
import json
import math
import re
import statistics
from collections import Counter, defaultdict
from pathlib import Path
import runpy
import yaml

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SOURCES = {}
ORIGINAL = "data/eval"
DEV = "data/benchmarks/olist-v3-dev-runtime-candidate"
FORMAL = "data/benchmarks/olist-v3-candidate"
CAPTURES = [("original200", ORIGINAL, "capture-original-200.json"),
            ("v3dev100", DEV, "capture-v3-dev-100.json"),
            ("v3formal100", FORMAL, "capture-v3-formal-100.json")]
R0 = "reports/stage2-v3-runtime-comparison-20261119"
R1 = "reports/stage2-v3-runtime-comparison-continued-20261119"
RUNS = [("DEV_C", "dev", R0, "eval-20261119-122736.json"),
        ("B1", "formal", R0, "eval-20261119-123600.json"),
        ("C1", "formal", R1, "eval-20261119-125741.json"),
        ("C2", "formal", R1, "eval-20261119-130557.json"),
        ("B2", "formal", R1, "eval-20261119-131314.json"),
        ("B3", "formal", R1, "eval-20261119-132022.json"),
        ("C3", "formal", R1, "eval-20261119-132841.json")]
SOURCE_CODE = ["src/main/java/com/text2sql/agent/generation/PromptTemplate.java",
    "src/main/java/com/text2sql/agent/generation/PromptFingerprint.java",
    "src/main/java/com/text2sql/agent/retrieval/HybridSchemaProvider.java",
    "src/main/java/com/text2sql/agent/retrieval/LexicalSchemaRetriever.java",
    "src/main/java/com/text2sql/agent/retrieval/SchemaDdlRenderer.java",
    "src/main/java/com/text2sql/agent/retrieval/DatabaseSchemaReader.java",
    "src/main/java/com/text2sql/agent/retrieval/ColumnPruner.java",
    "src/main/java/com/text2sql/agent/retrieval/ValueRetriever.java",
    "src/main/java/com/text2sql/agent/semantic/MetricRegistry.java",
    "src/main/java/com/text2sql/agent/semantic/Metric.java",
    "src/main/java/com/text2sql/agent/semantic/DimensionPolicy.java",
    "src/main/java/com/text2sql/agent/semantic/EntityPolicy.java",
    "src/main/java/com/text2sql/agent/fewshot/ExampleSelector.java",
    "src/main/java/com/text2sql/agent/budget/RequestBudget.java",
    "src/main/java/com/text2sql/agent/orchestrator/AgentResponse.java",
    "src/test/java/com/text2sql/agent/retrieval/PromptDeterminismProbe.java",
    "scripts/audit_prompt_composition.py", "src/main/resources/schema/metrics.yml",
    "src/main/resources/schema/glossary.yml", "src/main/resources/fewshot/examples.yml"]


def sha(data):
    if isinstance(data, str):
        data = data.encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def read_bytes(relative):
    p = (ROOT / relative).resolve()
    if not p.is_relative_to(ROOT):
        raise ValueError("Source outside workspace")
    # No recursive report discovery: all runtime and dataset sources are explicit public paths.
    if any(x.lower() in {"blind", "holdout", "u_b", "u_c"} or "blind" in x.lower()
           or "holdout" in x.lower() for x in p.relative_to(ROOT).parts):
        raise ValueError("Forbidden source")
    data = p.read_bytes()
    SOURCES[p.relative_to(ROOT).as_posix()] = {"sha256": sha(data), "bytes": len(data)}
    return data


def read_json(relative):
    return json.loads(read_bytes(relative).decode("utf-8-sig"))


def read_text(relative):
    return read_bytes(relative).decode("utf-8-sig")


def write_json(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(name, rows):
    with (OUT / name).open("w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_csv(name, rows):
    if not rows:
        return
    with (OUT / name).open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        for row in rows:
            writer.writerow({k: json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else v
                             for k, v in row.items()})


def utf16(s):
    return len(s.encode("utf-16-le")) // 2


def stats(values):
    values = sorted(v for v in values if v is not None)
    return {"knownCount": len(values), "sum": sum(values), "mean": statistics.mean(values),
            "p50": statistics.median(values), "p95NearestRank": values[math.ceil(len(values)*.95)-1],
            "max": max(values)} if values else {"knownCount": 0, "sum": None, "mean": None,
                                              "p50": None, "p95NearestRank": None, "max": None}


def dataset_items(directory):
    result = {}
    files = sorted((ROOT / directory).glob("T[0-9]*_*.yaml"))
    for path in files:
        source = path.relative_to(ROOT).as_posix()
        text = read_text(source)
        data = yaml.safe_load(text)
        lines = {m.group(1): text[:m.start()].count("\n")+1
                 for m in re.finditer(r"(?m)^- id: (\S+)\s*$", text)}
        for item in data:
            if item["id"] in result:
                raise ValueError("Duplicate public ID")
            result[item["id"]] = {**item, "source": source, "sourceLine": lines[item["id"]]}
    return result


def source_line(path, fragment):
    first = next((s.strip() for s in fragment.splitlines() if s.strip()), "")
    for i, line in enumerate(read_text(path).splitlines(), 1):
        if line.strip() == first:
            return i
    return None


def summarize_runtime():
    requests, calls, summaries, by_run = [], [], [], {}
    for run, split, folder, filename in RUNS:
        source = f"{folder}/{run}/{filename}"
        d = read_json(source)
        rows = d["itemEvidence"]
        assert len(rows) == len({x['id'] for x in rows}) == 100
        log_source = f"{folder}/{run}/llm-calls/llm-calls-20261119.jsonl"
        log_rows = [json.loads(line) for line in read_text(log_source).splitlines() if line.strip()]
        logs = {}
        for i, log in enumerate(log_rows):
            key = (log["requestId"], log["callNumber"])
            assert key not in logs
            logs[key] = (log, i+1)
        matched_logs = set()
        run_calls = []
        for index, item in enumerate(rows):
            evidence_source = f"{source}#/itemEvidence/{index}"
            budget = item.get("budget") or {}
            raw_stage = budget.get("stageMs") or {}
            timing = item.get("timings") or {}
            item_calls = []
            for ci, call in enumerate(item.get("calls", [])):
                key = (budget.get("requestId"), call["number"])
                pair = logs.get(key)
                log, line = pair if pair else (None, None)
                if log:
                    matched_logs.add(key)
                    assert log["promptSha256"] == call["promptSha256"]
                usage = call.get("usage")
                cr = {"run": run, "id": item["id"], "split": split,
                      "callNumber": call["number"], "kind": "first" if call["number"] == 1 else "correction",
                      "status": call["status"], "promptSha256": call["promptSha256"],
                      "callStarted": True,
                      "sendEvidence": "CONFIRMED_BY_PROVIDER_RESPONSE" if usage is not None else "UNKNOWN",
                      "usageKnown": usage is not None,
                      "latencyMs": usage.get("latencyMs") if usage else None,
                      "promptChars": usage.get("promptChars") if usage else None,
                      "promptTokens": usage.get("promptTokens") if usage else None,
                      "completionTokens": usage.get("completionTokens") if usage else None,
                      "cacheHitTokens": usage.get("cacheHitTokens") if usage else None,
                      "cacheMissTokens": usage.get("cacheMissTokens") if usage else None,
                      "knownCostYuan": usage.get("costYuan") if usage else None,
                      "logMatched": bool(log), "source": f"{evidence_source}/calls/{ci}",
                      "logSource": f"{log_source}:{line}" if line else None}
                item_calls.append(cr)
                calls.append(cr)
                run_calls.append(cr)
            assert len(item_calls) == budget.get('modelCalls'), 'Call trace and budget count differ'
            assert sum(not c['usageKnown'] for c in item_calls) == budget.get('unknownUsageCalls'), 'Unknown usage count differs'
            first = next((x for x in item_calls if x['callNumber'] == 1), None)
            correction = next((x for x in item_calls if x['callNumber'] == 2), None)
            exclusive = budget.get("timingsVersion") == "request-budget-exclusive-v1"
            if exclusive:
                assert timing["generationMs"] == sum(raw_stage.get(s, 0) for s in ("MODEL", "GENERATION", "REWRITE"))
                assert timing["validationMs"] == sum(raw_stage.get(s, 0) for s in ("VALIDATION", "RESULT_CHECK"))
                assert timing["executionMs"] == sum(raw_stage.get(s, 0) for s in ("EXECUTION", "EXPLAIN"))
                assert timing["totalMs"] == sum(timing[k] for k in ("retrievalMs", "generationMs", "validationMs", "executionMs")) + budget["otherMs"]
            stage = max(raw_stage, key=raw_stage.get) if raw_stage else None
            recorded_model_wait = raw_stage.get("MODEL")
            total = budget.get("elapsedMs")
            ratio = recorded_model_wait / total if recorded_model_wait is not None and total else None
            unknown_calls = budget.get("unknownUsageCalls")
            row = {"run": run, "split": split, "id": item["id"], "difficulty": item["difficulty"],
                   "promptVersion": d['meta']['promptVersion'], "status": item['status'], "correct": item['correct'],
                   "wallLatencyMs": item.get('wallLatencyMs'), "responseTotalMs": timing.get('totalMs'),
                   "budgetElapsedMs": total, "timingsVersion": budget.get('timingsVersion'),
                   "exclusiveAccountingVerified": exclusive,
                   "firstCallStarted": first is not None, "firstCallLatencyMs": first['latencyMs'] if first else None,
                   "firstCallTraceState": "CALL_TRACE_PRESENT" if first else "NO_FIRST_CALL_TRACE",
                   "firstCallSendEvidence": first['sendEvidence'] if first else "UNKNOWN",
                   "correctionStarted": correction is not None, "correctionLatencyMs": correction['latencyMs'] if correction else None,
                   "correctionTraceState": "CALL_TRACE_PRESENT" if correction else "NO_CORRECTION_CALL_TRACE",
                   "correctionSendEvidence": correction['sendEvidence'] if correction else "UNKNOWN",
                   "recordedModelWaitMs": recorded_model_wait, "recordedModelWaitShareOfBudget": ratio,
                   "recordedRetrievalMs": raw_stage.get('RETRIEVAL'),
                   "recordedSqlValidationMs": raw_stage.get('VALIDATION'),
                   "recordedResultCheckMs": raw_stage.get('RESULT_CHECK'),
                   "recordedDbExplainMs": raw_stage.get('EXPLAIN'), "recordedDbExecutionMs": raw_stage.get('EXECUTION'),
                   "otherMs": budget.get('otherMs'), "currentStageMs": budget.get('currentStageMs'),
                   "modelCalls": budget.get('modelCalls'), "unknownUsageCalls": unknown_calls,
                   "callLatencyKnownCount": sum(c['latencyMs'] is not None for c in item_calls),
                   "knownCallsLatencySubtotalMs": sum(c['latencyMs'] for c in item_calls if c['latencyMs'] is not None) if any(c['latencyMs'] is not None for c in item_calls) else None,
                   "dominantRecordedStage": stage, "termination": budget.get('termination'),
                   "terminalStage": budget.get('stage'), "retryTrigger": item.get('retryTrigger'),
                   "retrySkippedReason": budget.get('retrySkippedReason'),
                   "slowDefinition": "wallLatencyMs>=8000 or REQUEST_TIMEOUT",
                   "isSlow": item.get('wallLatencyMs', 0) >= 8000 or item['status'] == 'REQUEST_TIMEOUT',
                   "attributionLimit": "aggregate stages; per-attempt validation/DB unavailable; absent stages are null; legacy response zeros do not prove no work" if not exclusive else "exclusive aggregate stages verified; first/correction split available only for returned calls; interrupted provider usage unknown",
                   "source": evidence_source, "recordedStageMs": raw_stage,
                   "responseTimingsRaw": timing, "productionAttempts": item.get('productionAttempts')}
            requests.append(row)
        assert len(matched_logs) == len(logs), f"Unmatched log records in public run {run}"
        run_rows = [x for x in requests if x['run'] == run]
        slow = [x for x in run_rows if x['isSlow']]
        summary = {"run": run, "split": split, "promptVersion": d['meta']['promptVersion'],
                   "requests": len(rows), "correct": sum(x['correct'] for x in rows),
                   "statusCounts": dict(Counter(x['status'] for x in rows)),
                   "reportedP95LatencyMs": d['overall']['p95LatencyMs'], "wallLatencyStats": stats(x['wallLatencyMs'] for x in run_rows),
                   "exclusiveTimingRequests": sum(x['exclusiveAccountingVerified'] for x in run_rows),
                   "firstCallsStarted": sum(x['kind']=='first' for x in run_calls), "correctionCallsStarted": sum(x['kind']=='correction' for x in run_calls),
                   "firstCallsConfirmedByProviderResponse": sum(x['kind']=='first' and x['sendEvidence']=='CONFIRMED_BY_PROVIDER_RESPONSE' for x in run_calls),
                   "firstCallsSendEvidenceUnknown": sum(x['kind']=='first' and x['sendEvidence']=='UNKNOWN' for x in run_calls),
                   "correctionCallsConfirmedByProviderResponse": sum(x['kind']=='correction' and x['sendEvidence']=='CONFIRMED_BY_PROVIDER_RESPONSE' for x in run_calls),
                   "correctionCallsSendEvidenceUnknown": sum(x['kind']=='correction' and x['sendEvidence']=='UNKNOWN' for x in run_calls),
                   "unknownUsageCalls": sum(x['unknownUsageCalls'] for x in run_rows),
                   "knownUsageCalls": sum(x['usageKnown'] for x in run_calls),
                   "knownCostSubtotalYuan": sum(x['knownCostYuan'] for x in run_calls if x['knownCostYuan'] is not None),
                   "completeCostKnown": all(x['usageKnown'] for x in run_calls),
                   "firstKnownLatencyStats": stats(x['latencyMs'] for x in run_calls if x['kind']=='first'),
                   "correctionKnownLatencyStats": stats(x['latencyMs'] for x in run_calls if x['kind']=='correction'),
                   "slowRequests": len(slow), "slowWithCorrectionStarted": sum(x['correctionStarted'] for x in slow),
                   "slowCorrectionsConfirmedByProviderResponse": sum(x['correctionStarted'] and x['correctionSendEvidence']=='CONFIRMED_BY_PROVIDER_RESPONSE' for x in slow),
                   "slowCorrectionsSendEvidenceUnknown": sum(x['correctionStarted'] and x['correctionSendEvidence']=='UNKNOWN' for x in slow),
                   "slowDominantStageCounts": dict(Counter(x['dominantRecordedStage'] for x in slow)),
                   "slowModelWaitShareStats": stats(x['recordedModelWaitShareOfBudget'] for x in slow),
                   "slowModelWaitAtLeast90Percent": sum((x['recordedModelWaitShareOfBudget'] or 0)>=.9 for x in slow),
                   "sources": [source, log_source]}
        summaries.append(summary)
        by_run[run] = (d, source)
    # Duplicate archive files are not extra experiments or extra denominators.
    duplicates = []
    for run, _, folder, filename in RUNS[:2]:
        left, right = f'{folder}/{run}/{filename}', f'{R1}/{run}/{filename}'
        duplicates.append({"canonical": left, "duplicate": right, "byteIdentical": read_bytes(left)==read_bytes(right)})
    write_json("runtime-summary.json", {"format": "replan-stage-a-runtime-v1", "newModelCalls": 0,
        "scope": "DEV_C and formal B1/B2/B3/C1/C2/C3 only; no unseen/blind/holdout reads",
        "runs": summaries, "deduplicatedArchivedReports": duplicates,
        "requestCount": len(requests), "recordedStartedCallCount": len(calls),
        "sendEvidenceCounts": dict(Counter(x['sendEvidence'] for x in calls)),
        "correctionStartedCount": sum(x['kind']=='correction' for x in calls),
        "correctionSendEvidenceCounts": dict(Counter(x['sendEvidence'] for x in calls if x['kind']=='correction')),
        "callCountingLimit": "startModelCall reserves before transport; unknown/in-flight records do not independently prove HTTP transmission or provider billing",
        "unknownUsageCalls": sum(x['unknownUsageCalls'] for x in requests),
        "knownCostSubtotalYuan": sum(x['knownCostYuan'] for x in calls if x['knownCostYuan'] is not None),
        "costConclusion": "Known subtotal only. Unknown provider usage is not zero; complete cost and per100 cost pass remain unproved."})
    write_jsonl("request-segments.jsonl", requests)
    write_csv("request-segments.csv", [{k:v for k,v in x.items() if k not in ('recordedStageMs','responseTimingsRaw','productionAttempts')} for x in requests])
    write_csv("slow-request-attribution.csv", [{k:v for k,v in x.items() if k not in ('recordedStageMs','responseTimingsRaw','productionAttempts')} for x in sorted(requests,key=lambda r:r['wallLatencyMs'],reverse=True) if x['isSlow']])
    write_jsonl("model-call-ledger.jsonl", calls)
    return by_run, summaries, requests, calls


def summarize_inputs(by_run):
    summaries, inventory, fragments = [], [], {}
    missing = []
    source_prompt = 'src/main/java/com/text2sql/agent/generation/PromptTemplate.java'
    def fragment(kind, identity, text, source, line=None):
        key = kind + ':' + sha(text)
        if key not in fragments:
            fragments[key] = {'fragmentId':key,'kind':kind,'identity':identity,'text':text,
                              'chars':utf16(text),'sha256':sha(text),'source':source,'sourceLine':line}
        return key
    for label, directory, filename in CAPTURES:
        if not (OUT / filename).exists():
            missing.append(filename)
            continue
        rel_capture = (OUT / filename).relative_to(ROOT).as_posix()
        capture = read_json(rel_capture)
        assert capture['modelCalls']==capture['modelCostYuan']==0
        assert capture['datasetDirectory']==directory
        public = dataset_items(directory)
        assert len(public)==capture['itemCount']
        system = capture['systemPrompt']['text']
        assert utf16(system)==capture['systemPrompt']['chars']
        assert sha(system)==capture['systemPrompt']['sha256']
        archived_original = {}
        if label == 'original200':
            for split in ('dev', 'eval'):
                old_path=f'reports/period-ranking-20261119/prompt-{split}.json'
                old=read_json(old_path)
                assert old['datasetSha256']==capture['datasetSha256']
                assert old['schemaSha256']==capture['schemaSha256']
                assert old['systemPromptSha256']==capture['systemPrompt']['sha256']
                for oi, old_item in enumerate(old['items']):
                    archived_original[old_item['id']] = (old_item, f'{old_path}#/items/{oi}')
            assert len(archived_original)==200
        # Partition actual system text into mutually exclusive blocks, retaining every separator.
        boundaries=[(0,'preamble')]
        for m in re.finditer(r'(?m)^(?:思考与推理步骤.*|硬性规则：|\d+\. )',system):
            boundaries.append((m.start(), 'rule:'+m.group(0).strip()))
        boundaries=sorted(dict(boundaries).items())
        system_ids=[]
        for i,(start,identity) in enumerate(boundaries):
            text=system[start:boundaries[i+1][0] if i+1<len(boundaries) else len(system)]
            if text:
                system_ids.append(fragment('system',identity,text,source_prompt,source_line(source_prompt,text)))
        assert sum(fragments[k]['chars'] for k in system_ids)==utf16(system)
        def_ids={}
        metric_source='src/main/resources/schema/metrics.yml'
        metric_text=read_text(metric_source)
        metric_lines={m.group(1):metric_text[:m.start()].count('\n')+1
                      for m in re.finditer(r'(?m)^  - name: (\S+)\s*$', metric_text)}
        for definition in capture['metricDefinitions']+capture['dimensionPolicies']+capture['entityPolicies']:
            rendered=definition['rendered']
            name=definition['name']
            def_ids[name]=fragment(definition['kind'],name,rendered['text'],rendered['source'],metric_lines.get(name))
        example_source='src/main/resources/fewshot/examples.yml'
        ex_text=read_text(example_source)
        example_lines={m.group(1):ex_text[:m.start()].count('\n')+1 for m in re.finditer(r'(?m)^- id: (\S+)\s*$',ex_text)}
        row_matches=[]
        historical_send_evidence=[]
        original_matches=[]
        for index,item in enumerate(capture['items']):
            original=public[item['id']]
            assert item['question']==original['question']
            assert sha(item['question'])==item['questionSha256']
            assert item['promptChars']==utf16(system)+utf16(item['userPrompt']['text'])
            assert sum(item['componentChars'].values())==item['promptChars']
            for key in ('ddlAndJoinHints','dataProfile','metrics','valueHints'):
                assert utf16(item[key]['text'])==item[key]['chars']
                assert sha(item[key]['text'])==item[key]['sha256']
            selected={}
            for key in ('ddlAndJoinHints','dataProfile','metrics','valueHints'):
                selected[key]=fragment(key,item['id'],item[key]['text'],item[key]['source'])
            selected['systemRules']=system_ids
            selected['definitions']=[def_ids[x['name']] for x in item['injectedDefinitionIdentities']]
            selected['examples']=[]
            for ex in item['selectedExamples']:
                qid=fragment('exampleQuestion',ex['id'],ex['question'].strip(),example_source,example_lines.get(ex['id']))
                sid=fragment('exampleSql',ex['id'],ex['sql'].strip(),example_source,example_lines.get(ex['id']))
                selected['examples'].append({'id':ex['id'],'questionFragment':qid,'sqlFragment':sid,'tables':ex['tables']})
            historical=[]
            offline_archive=None
            if archived_original:
                old_item, old_source=archived_original[item['id']]
                same = old_item['promptSha256']==item['promptSha256'] and old_item['componentChars']==item['componentChars']
                assert same, 'Current reconstruction differs from archived p21 original public capture'
                original_matches.append(same)
                offline_archive={'source':old_source,'promptHashEqual':True,'allComponentLengthsEqual':True,
                    'providerSendEvidence':'UNKNOWN','providerTransmissionClaimed':False}
            for run,(d,source) in by_run.items():
                if label=='original200':
                    continue
                if (label=='v3dev100') != (run=='DEV_C') or not d['meta']['promptVersion'].startswith('p21'):
                    continue
                real=next(x for x in d['itemEvidence'] if x['id']==item['id'])
                assert real['questionSha256']==item['questionSha256']
                first=next((c for c in real['calls'] if c['number']==1),None)
                equality='NO_FIRST_CALL_TRACE' if first is None else 'HASH_EQUAL' if first['promptSha256']==item['promptSha256'] else 'HASH_DIFFERENT'
                known_chars=first['usage']['promptChars'] if first and first.get('usage') else None
                if equality=='HASH_EQUAL' and known_chars is not None:
                    assert known_chars==item['promptChars']
                historical.append({'run':run,'equality':equality,'recordedPromptSha256':first['promptSha256'] if first else None,
                    'firstCallStarted':first is not None,
                    'sendEvidence':'CONFIRMED_BY_PROVIDER_RESPONSE' if first and first.get('usage') is not None else 'UNKNOWN',
                    'actualCharsIfReturned':known_chars,'source':f'{source}#/itemEvidence/'+str(next(i for i,x in enumerate(d['itemEvidence']) if x['id']==item['id']))})
                row_matches.append(equality)
                if first:
                    historical_send_evidence.append(historical[-1]['sendEvidence'])
            inventory.append({'corpus':label,'id':item['id'],'source':original['source'],'sourceLine':original['sourceLine'],
                'captureSource':f'{rel_capture}#/items/{index}','question':item['question'],
                'promptChars':item['promptChars'],'promptSha256':item['promptSha256'],'componentChars':item['componentChars'],
                'fragmentIds':selected,'selectedTables':[t['name'] for t in item['schemaTablesAfterPruning']],
                'retrievalTables':item['retrieval']['tableNames'],'expandedTables':item['retrieval']['expandedTables'],
                'directHitsInPublishedEvidence':item['directHitsInPublishedEvidence'],'providerAddedTables':item['providerAddedTables'],
                'historicalFirstCallComparison':historical,'archivedOfflineCaptureComparison':offline_archive})
        components={key:sum(x['componentChars'][key] for x in capture['items']) for key in capture['items'][0]['componentChars']}
        total=sum(components.values())
        summaries.append({'corpus':label,'datasetDirectory':directory,'datasetSha256':capture['datasetSha256'],
            'sampleIdsSha256':capture['sampleIdsSha256'],'items':capture['itemCount'],'captureSource':rel_capture,
            'lengthUnit':capture['lengthUnit'],'promptStats':stats(x['promptChars'] for x in capture['items']),
            'componentTotalChars':components,'componentPercent':{k:v/total*100 for k,v in components.items()},
            'systemCharsPerPrompt':utf16(system),'systemPartitions':len(system_ids),
            'historicalFirstCallComparisonCounts':dict(Counter(row_matches)),
            'historicalStartedFirstCallSendEvidenceCounts':dict(Counter(historical_send_evidence)),
            'archivedP21OriginalCaptureMatches':sum(original_matches) if original_matches else None,
            'resourceSha256':capture['resourceSha256'], 'schemaSha256':capture['schemaSha256']})
    write_json('input-summary.json',{'format':'replan-stage-a-input-summary-v1','modelCalls':0,
        'lengthUnit':'java-utf16-code-units; not tokens','corpora':summaries,'missingCaptures':missing,
        'actualTextClaim':'Raw historical prompt text was not logged. Matching canonical message SHA256 plus known length is evidence for equality with a recorded started-call message, not proof of HTTP transmission or a recovered provider transcript. NO_FIRST_CALL_TRACE means no start trace was found, not proof of no transmission.',
        'scopeLimit':'p9 is represented by actual historical prompt hashes/lengths/tokens only; no p9 component reconstruction was run.',
        'productionPromptChanged':False})
    write_jsonl('request-input-inventory.jsonl',inventory)
    write_jsonl('input-fragments.jsonl',fragments.values())
    write_csv('input-fragment-index.csv',[{k:v for k,v in row.items() if k!='text'} for row in fragments.values()])
    system_partition_total=sum(row['chars'] for row in fragments.values() if row['kind']=='system')
    write_csv('system-rule-inventory.csv',[{**{k:v for k,v in row.items() if k!='text'},
        'shareOfSystemPercent':100*row['chars']/system_partition_total} for row in fragments.values() if row['kind']=='system'])
    write_csv('definition-inventory.csv',[{k:v for k,v in row.items() if k!='text'}
        for row in fragments.values() if row['kind'] in ('metric','dimensionPolicy','entityPolicy')])
    write_csv('request-input-inventory.csv',[{'corpus':x['corpus'],'id':x['id'],'question':x['question'],
        **x['componentChars'],'totalChars':x['promptChars'],'promptSha256':x['promptSha256'],
        'selectedTables':x['selectedTables'],'source':x['source'],'sourceLine':x['sourceLine'],'captureSource':x['captureSource'],
        'historicalFirstCallComparison':x['historicalFirstCallComparison']} for x in inventory])
    return summaries, missing


def markdown(inputs, runtime, requests, calls, missing):
    lines=['# A阶段公开输入与慢请求证据清单','',
        '本目录只整理公开资料和本机只读重建；供应商新调用0、费用0。生产提示词、TopK=8、关系衰减0.4与桥接上限2均未改。','',
        '免费capture由主任务统一编译/执行：运行生产类直接解自冻结p21 JAR，类路径为`target/replan-stage-a-frozen/BOOT-INF/classes`及其79个依赖；未用当前工作树重新编译的`target/classes`。探针单独编译到本目录classes。JAR来源SHA256为`e8a3db8efba97e675432b995018b23b016088604cc446513c2d84f85703fe764`；实际类/资源与依赖指纹见`execution-provenance.json`，完整版本与数据库身份由主任务清单固定。','',
        '## 输入覆盖与来源','',
        '字符单位统一为Java UTF-16 code unit，不把字符数冒充Token。完整系统/用户文本及组件在capture JSON，去重片段全文在`input-fragments.jsonl`；`request-input-inventory.csv/jsonl`逐题连接题面来源、字符数、表列来源及历史首答。','',
        '|公开语料|题数|总字符|平均|P95|系统占比|历史首答指纹比较|','|---|---:|---:|---:|---:|---:|---|']
    for x in inputs:
        s=x['promptStats']; lines.append(f"|{x['corpus']}|{x['items']}|{s['sum']}|{s['mean']:.2f}|{s['p95NearestRank']}|{x['componentPercent']['system']:.2f}%|{json.dumps(x['historicalFirstCallComparisonCounts'],ensure_ascii=False)}|")
    lines += ['', '原200的新重建与已有period-ranking公开capture逐题核对：200/200提示词指纹与全部组件长度相同。这是离线捕获复用核验，不是新增200次模型请求。系统片段与口径的独立索引见`system-rule-inventory.csv`和`definition-inventory.csv`，均有源文件/行号、字符数和全文片段指纹。', '',
        '系统规则→`PromptTemplate.systemPrompt`；框架与使用原则→`userPrompt`；DDL→当前只读数据库目录和`SchemaDdlRenderer`；指标/维度/实体口径→`metrics.yml`及各render方法；示例→`examples.yml`与`ExampleSelector`；枚举→`glossary.yml`与`ValueRetriever`。数据画像来自数据库读取器。每个源文件及输入证据的SHA256见`source-manifest.json`。', '',
        '检索身份未扁平化：捕获保留`retrieval.evidence/scores/expandedTables/fellBack`、剪枝后的实际表列和provider补表。`directHitsInPublishedEvidence`仅由已发布证据减扩展集合推导，生产方法内局部`directHits`未直接导出，不伪称抓到了原局部变量。指标另存`metricMatchEvidence`的MATCHED/缺上下文/排除原因，以及实际注入定义身份；依赖表补入和实体/维度列可用性仍可追溯。', '',
        '不改变原选择机制：关系扩展分数取源分×0.4×边权，多路径取最大而不累加；直接命中不被扩展覆盖；同分才按直接命中优先、表名字典序裁决。它不能保证所有直接命中永不被更高分扩展挤出，桥接最多补2表也可能仍不连通。完全无命中回退全目录，未裁剪的全量路径不会强补示例。此处只留证，不调参数。', '',
        '历史日志未保留原始prompt正文（prompt=null）。HASH_EQUAL表示重建消息与已记录的启动消息指纹一致，已返回调用再核字符数。V3共有390条首答启动记录匹配；另10条标为NO_FIRST_CALL_TRACE，只说明没有首答启动轨迹，不能据此断言没有HTTP发送。逐条sendEvidence仅取CONFIRMED_BY_PROVIDER_RESPONSE（有供应商返回用量）或UNKNOWN（证据不足）；哈希相同本身不证明HTTP发送。p9只保留历史记录的哈希、字符与Token，不借用p21的组件数。原始capture保持字节不变，其中captureScope的sent措辞不得越过本段发送证据边界。', '',
        '## 已有首答、纠错与慢请求','',
        '7个公开批次合计700请求；父目录与continued内重复的DEV_C/B1先核对字节后去重。仅DEV_C和正式B1/B2/B3/C1/C2/C3进入清单，未读blind/holdout/未见题或逐题结果。慢请求预定义为wallLatencyMs≥8000或REQUEST_TIMEOUT，用于定位；不是验收门槛。','',
        '|批次|正确|P95 ms|超时|首答/纠错启动|未知用量调用|慢请求|慢请求主要记录阶段|',
        '|---|---:|---:|---:|---:|---:|---:|---|']
    for x in runtime:
        lines.append(f"|{x['run']}|{x['correct']}/100|{x['reportedP95LatencyMs']}|{x['statusCounts'].get('REQUEST_TIMEOUT',0)}|{x['firstCallsStarted']}/{x['correctionCallsStarted']}|{x['unknownUsageCalls']}|{x['slowRequests']}|{json.dumps(x['slowDominantStageCounts'],ensure_ascii=False)}|")
    slow=[x for x in requests if x['isSlow']]
    lines += ['',f"慢请求共{len(slow)}条，其中{sum(x['correctionStarted'] for x in slow)}条有第二次调用启动记录：{sum(x['correctionStarted'] and x['correctionSendEvidence']=='CONFIRMED_BY_PROVIDER_RESPONSE' for x in slow)}条获供应商返回确认，{sum(x['correctionStarted'] and x['correctionSendEvidence']=='UNKNOWN' for x in slow)}条发送证据与用量未知；不得统称为已发送纠错。{sum((x['recordedModelWaitShareOfBudget'] or 0)>=.9 for x in slow)}条的记录MODEL等待至少占预算经过时间90%。这能说明观察到的等待落在哪一段，不能证明文本增长是唯一原因，也不能从266组约+82%的整批证据推出任一单规则因果。",'',
        '`request-segments.jsonl`保留原始分段与productionAttempts；CSV便于筛选；`slow-request-attribution.csv`逐条给出首答、纠错、MODEL聚合等待、SQL校验、结果检查、EXPLAIN、执行与源JSON指针；`model-call-ledger.jsonl`按requestId+callNumber匹配原日志。', '',
        'p21的400条请求有`request-budget-exclusive-v1`：MODEL+GENERATION+REWRITE投影到generation，VALIDATION+RESULT_CHECK投影到validation，EXPLAIN+EXECUTION投影到execution，四段+otherMs=total，已逐条核验。MODEL阶段是本地调用等待，含准备/网络/供应商推理及输出接收，不能继续拆出排队、首Token或解码。校验与DB阶段只提供请求累计数，不能精确拆成首答/纠错各自的校验/DB耗时。', '',
        'p9的300条没有独占计时版本和otherMs，不强行套p21公式。特别是超时响应timings多段为0时，budget.stageMs仍记录MODEL等待；这些0不解释为模型未耗时。缺失stage字段、未返回调用latency/Token/费用全部记null，不回填0。没有纠错启动轨迹标为NO_CORRECTION_CALL_TRACE，不能提升为没有发送的证明，也不伪装为测得0ms。', '',
        f"历史记录启动调用{len(calls)}次，其中{sum(x['usageKnown'] for x in calls)}次有返回用量并匹配逐调用日志，{sum(not x['usageKnown'] for x in calls)}次用量未知。startModelCall在transport前预留，因此未知/IN_FLIGHT记录不独立证明HTTP已发送或供应商已计费。已知费用小计¥{sum(x['knownCostYuan'] for x in calls if x['knownCostYuan'] is not None):.8f}只是下界，不能据此宣称完整成本达标。", '',
        '## 可复核入口与缺证','',
        '执行`python reports/replan-stage-a-20261119/input-inventory/build_inventory.py`只重整此目录输出，不调用模型。capture的Java入口只允许原200和两个公开V3目录，拒绝其他目录和覆盖旧capture；执行时未构造模型客户端。历史`audit_prompt_composition.py --evidence-only`仅用于核验20261116的旧组件档案，不能跨版本断言p21系统长度仍为2795。', '',
        '缺证：历史原始消息正文、p9分组件正文、未知调用的供应商Token/完整费用、网络/供应商内部耗时、首答与纠错各自的校验/DB精细分段。当前记录不支持单条规则的准确率/延迟因果；本阶段未取得新业务准确率或性能改善，只固定证据。']
    if missing:
        lines+=['', '尚缺免费capture：'+', '.join(missing)]
    (OUT/'README.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')


def main():
    for path in SOURCE_CODE:
        read_bytes(path)
    by_run,runtime,requests,calls=summarize_runtime()
    inputs,missing=summarize_inputs(by_run)
    frozen='target/replan-stage-a-frozen/BOOT-INF'
    runtime_files={}
    for base in ('classes','lib'):
        for p in sorted((ROOT/frozen/base).rglob('*')):
            if p.is_file():
                rel=p.relative_to(ROOT).as_posix()
                data=read_bytes(rel)
                runtime_files[rel]={'sha256':sha(data),'bytes':len(data)}
    for log in ('capture-original.log','capture-dev.log','capture-formal.log'):
        if (OUT/log).exists():
            read_bytes((OUT/log).relative_to(ROOT).as_posix())
    write_json('execution-provenance.json',{'executor':'parent agent unified compilation/execution',
        'runtimeOrigin':'production classes and dependency JARs extracted directly from frozen p21 application JAR',
        'sourceJarSha256ReportedByExecutor':'e8a3db8efba97e675432b995018b23b016088604cc446513c2d84f85703fe764',
        'productionClassPath':frozen+'/classes','dependencyDirectory':frozen+'/lib',
        'dependencyJarCount':sum(p.endswith('.jar') for p in runtime_files),
        'probeClassesDirectory':(OUT/'classes').relative_to(ROOT).as_posix(),
        'currentTargetClassesUsed':False,'modelClientCreated':False,
        'runtimeFileFingerprints':runtime_files})
    # Historical auditor is intentionally version-specific; this does not audit current p21 text.
    auditor=runpy.run_path(str(ROOT/'scripts/audit_prompt_composition.py'))
    audit=auditor['audit'](ROOT,ROOT/'reports/prompt-composition-20261116',True)
    for name in ('summary.json','dev.json','eval.json','artifacts.sha256.json'):
        read_bytes('reports/prompt-composition-20261116/'+name)
    for name in read_json('reports/prompt-composition-20261116/artifacts.sha256.json'):
        read_bytes('reports/prompt-composition-20261116/'+name)
    for split in ('dev','eval'):
        read_bytes(read_json('reports/prompt-composition-20261116/summary.json')['splits'][split]['referenceReport'])
    write_json('legacy-component-audit.json',audit)
    markdown(inputs,runtime,requests,calls,missing)
    write_json('source-manifest.json',{'scope':'explicit public inputs only','files':dict(sorted(SOURCES.items())),
        'newModelCalls':0,'newModelCostYuan':0,'missingCaptures':missing})
    write_json('artifacts.sha256.json',{p.relative_to(OUT).as_posix():sha(p.read_bytes()) for p in sorted(OUT.rglob('*'))
        if p.is_file() and p.name!='artifacts.sha256.json'})
    print(json.dumps({'publicRequests':len(requests),'historicalCalls':len(calls),
                      'capturedPublicItems':sum(x['items'] for x in inputs),'missingCaptures':missing,
                      'newModelCalls':0},ensure_ascii=False))


if __name__=='__main__':
    main()
