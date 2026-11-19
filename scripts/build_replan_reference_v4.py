"""Build a separate public V4 from independently audited V3; never modify V3 or score a candidate."""
from collections import Counter
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

import yaml

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from summarize_holdout import dataset_hash, ids_hash

AUDIT = ROOT / "reports/replan-stage-a-20261119/contract-audit/item-recommendations.json"
OUT = ROOT / "reports/replan-stage-a-20261119"
REVISE = "--revise-after-review" in sys.argv
SUITES = {
    "development": ("data/benchmarks/olist-v3-dev-runtime-candidate", "data/benchmarks/olist-v4-development-20261119"),
    "formal": ("data/benchmarks/olist-v3-candidate", "data/benchmarks/olist-v4-formal-20261119"),
}


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def write(path, value):
    with path.open("w" if REVISE else "x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main():
    if REVISE:
        decision = json.loads((OUT / "independent-review/decision-attempt1.json").read_text(encoding="utf-8"))
        if "REQUEST_CHANGES" not in json.dumps(decision) or not (OUT / "attempt1-source.zip").is_file():
            raise ValueError("Revision requires preserved package and explicit independent request changes")
        if (OUT / "v4-attempt2-built.json").exists():
            raise ValueError("Second package already built; no silent additional review attempt")
    proposal = json.loads(AUDIT.read_text(encoding="utf-8"))
    proposed = {(x["suite"], x["id"]): x for x in proposal["items"]}
    if len(proposed) != 200:
        raise ValueError("Expected independent per-item public specifications")
    typed_path = OUT / "output-column-schema-attempt2.json"
    typed = json.loads(typed_path.read_text(encoding="utf-8"))
    if len(typed["items"]) != 200 or typed["sourceSpecificationSha256"] != hashlib.sha256(AUDIT.read_bytes()).hexdigest():
        raise ValueError("Typed reference metadata does not match audited specification")
    scorer_sha = hashlib.sha256((OUT / "ReferenceContractScorer.java").read_bytes()).hexdigest()
    all_changes, seals = [], {}
    for suite, (source, destination) in SUITES.items():
        output = ROOT / destination
        output.mkdir(exist_ok=REVISE)
        policies, before, after = {}, [], []
        for path in sorted((ROOT / source).glob("T*.yaml")):
            old = yaml.safe_load(path.read_text(encoding="utf-8-sig"))
            new = []
            for item in old:
                identity = item["id"]
                rule = proposed[suite, identity]
                if digest(item["question"]) != rule["sourceQuestionSha256"] or digest(item["gold_sql"]) != rule["sourceGoldSqlSha256"]:
                    raise ValueError("Public source drift: " + identity)
                changed = copy.deepcopy(item)
                changed["question"] = rule["proposedQuestion"]
                changed["gold_sql"] = rule["proposedGoldSql"]
                new.append(changed)
                policy = copy.deepcopy(rule["scoring"])
                policy["keyPositions"] = ([0] if policy["mode"] == "TIME_ASC" else
                    [2 if suite == "formal" and identity == "T5-001" else 1]
                    if policy["mode"] == "PARTIAL_ORDER" else [])
                column_schema = typed["items"][suite + ":" + identity]
                if column_schema["goldSqlSha256"] != digest(changed["gold_sql"]) or column_schema["questionSha256"] != digest(changed["question"]):
                    raise ValueError("Typed reference item drift")
                policy["columnKinds"] = [c["kind"] for c in column_schema["columns"]]
                policy["goldOutputSchemaSha256"] = hashlib.sha256(json.dumps(column_schema, sort_keys=True).encode()).hexdigest()
                policies[identity] = policy
                if rule["questionChange"] or rule["goldSqlChange"]:
                    all_changes.append(rule)
                before.append(item)
                after.append(changed)
            # Unchanged files remain byte-identical even in the new reference.
            desired = (path.read_bytes() if new == old else yaml.safe_dump(new, allow_unicode=True, sort_keys=False,
                                                                          width=120).encode("utf-8"))
            target = output / path.name
            if target.exists():
                if not REVISE or target.read_bytes() != desired:
                    raise ValueError("Revision must not change previously checked Gold/question bytes")
            else:
                target.write_bytes(desired)
        if len(after) != 100 or len({x["id"] for x in after}) != 100:
            raise ValueError("Reference denominator/identity changed")
        quotas = Counter(x["difficulty"] for x in before)
        if quotas != Counter(x["difficulty"] for x in after):
            raise ValueError("Difficulty quotas changed")
        write(output / "scoring-policy.json", dict(format="public-reference-order-contract-v1", suite=suite,
              evaluator="reports/replan-stage-a-20261119/ReferenceContractScorer.java", policies=policies,
              evaluatorSourceSha256=scorer_sha, referenceColumnSchemaSha256=hashlib.sha256(typed_path.read_bytes()).hexdigest(),
              oldJarGradeIsDiagnosticOnly=True,
              paidAdmission="DENIED_UNTIL_D_COMMON_SCORER_AND_CONTROLLER_INTEGRATION"))
        files = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.iterdir())
                 if p.is_file() and p.name != "reference-seal.json"}
        seal = dict(format="public-reference-v4-seal-v1", state="REFERENCE_CANDIDATE_NOT_EVALUATION_ADMITTED",
            builtAt=datetime.now(timezone.utc).isoformat(), suite=suite, itemCount=100,
            sourceDirectory=source, sourceDatasetSha256=dataset_hash(ROOT / source),
            datasetSha256=dataset_hash(output), sampleIdsSha256=ids_hash(x["id"] for x in after),
            layerCounts=dict(quotas), files=files,
            questionChanges=sum(a["question"] != b["question"] for a, b in zip(before, after)),
            goldSqlChanges=sum(a["gold_sql"] != b["gold_sql"] for a, b in zip(before, after)),
            independentSpecificationSha256=hashlib.sha256(AUDIT.read_bytes()).hexdigest(),
            minimumFormalPercent=89, relativeRegressionLimitPp=2,
            providerCalls=0, providerTokens=0, providerCostYuan=0, stageAccepted=False,
            reviewAcceptance="reports/replan-stage-a-20261119/independent-review/decision.json")
        write(output / "reference-seal.json", seal)
        seals[suite] = seal
    write(OUT / "v4-errata.json", dict(format="public-reference-v4-errata-v1", items=all_changes,
        source="Independent public-contract audit, no candidate outputs used",
        historicalScoresUnchanged=True, referenceSummary=seals))
    original = {i["id"]: i for p in sorted((ROOT / "data/eval").glob("T*.yaml"))
                for i in yaml.safe_load(p.read_text(encoding="utf-8-sig"))}
    development = {i["id"]: i for p in sorted((ROOT / SUITES["development"][1]).glob("T*.yaml"))
                   for i in yaml.safe_load(p.read_text(encoding="utf-8-sig"))}
    targets = []
    for identity, failure in (("T4-001", "identity"), ("T3-002", "shape"), ("T6-003", "unit")):
        item, revised = original[identity], development[identity]
        # Strict byte equality is chosen in advance: no semantic equivalence discretion after a response.
        reuse = item["question"] == revised["question"] and item["gold_sql"] == revised["gold_sql"]
        targets.append(dict(id=identity, failureLayer=failure, originalQuestion=item["question"],
            originalGoldSql=item["gold_sql"], originalQuestionSha256=digest(item["question"]),
            originalGoldSqlSha256=digest(item["gold_sql"]),
            sameQuestion=item["question"] == revised["question"], sameGoldSql=item["gold_sql"] == revised["gold_sql"],
            confirmation="REUSE_FINAL_CANDIDATE_UNIQUE_DEV_RESPONSE" if reuse else "ONE_ORIGINAL_REQUEST_AFTER_DEV_BEFORE_FORMAL",
            originalScorer="HTTP200 + SUCCESS + !truncated + ResultNormalizer.normalize(actual,false).equals(expectedRows); exact original Gold; never SQL-aware tie comparison",
            requestLimit=0 if reuse else 1,
            contractBoundary="T4 original unspecified Top10 tie membership may conflict with stable identity default; strict failure remains failure. T6 original uses valid-order population; no changed denominator or unit is accepted."))
    write(OUT / "original-target-preregistration.json", dict(format="original-target-confirmation-v1", targets=targets,
          threshold="AT_LEAST_2_OF_3_STRICTLY", extraRequestsMax=sum(t["requestLimit"] for t in targets),
          actualModelCallsMax=2 * sum(t["requestLimit"] for t in targets),
          newBatchQuestionRequestMax=1000 + sum(t["requestLimit"] for t in targets),
          newBatchModelCallMax=2000 + 2 * sum(t["requestLimit"] for t in targets),
          sameQuestionAndGoldBytesRequiredToReuse=True, historicalEvidenceNotInherited=True,
          failureStopsBeforeFormal=True, selectedCandidateOnly=True,
          originalScorerIdentity=dict(
              probe="src/test/java/com/text2sql/agent/semantic/CustomerIdentityHttpProbe.java",
              probeSourceSha256=hashlib.sha256((ROOT / "src/test/java/com/text2sql/agent/semantic/CustomerIdentityHttpProbe.java").read_bytes()).hexdigest(),
              outputProbe="src/test/java/com/text2sql/agent/semantic/OutputContractHttpProbe.java",
              outputProbeSha256=hashlib.sha256((ROOT / "src/test/java/com/text2sql/agent/semantic/OutputContractHttpProbe.java").read_bytes()).hexdigest(),
              normalizeSource="src/main/java/com/text2sql/agent/execution/ResultNormalizer.java",
              normalizeSourceSha256=hashlib.sha256((ROOT / "src/main/java/com/text2sql/agent/execution/ResultNormalizer.java").read_bytes()).hexdigest(),
              method="normalize(QueryResult,false).equals(expectedRows)", rowOrderMatters=False,
              boundaryMembershipExact=True, sqlAwareTiesProhibited=True)))
    print(json.dumps({k: {f: v[f] for f in ("itemCount", "questionChanges", "goldSqlChanges")} for k, v in seals.items()}))
    if REVISE:
        write(OUT / "v4-attempt2-built.json", dict(reviewAttempt=2, yamlBytesUnchanged=True,
            scorerSourceSha256=scorer_sha, outputColumnSchemaSha256=hashlib.sha256(typed_path.read_bytes()).hexdigest(),
            replyTo="independent-review/decision-attempt1.json", paidAdmission=False))


if __name__ == "__main__":
    main()
