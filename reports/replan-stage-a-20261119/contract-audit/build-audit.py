"""Offline public-reference audit. Reads only the explicit public allowlist below."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import re
import yaml

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SUITES = {
    "formal": ROOT / "data/benchmarks/olist-v3-candidate",
    "development": ROOT / "data/benchmarks/olist-v3-dev-runtime-candidate",
}
PUBLIC_DOCS = [
    "AGENTS.md",
    "docs/REVISED_IMPROVEMENT_PLAN_20261119.md",
    "docs/STAGE2_REGRESSION_PROTOCOL_20261117.md",
    "docs/SEMANTIC_CONSISTENCY_MATRIX_20261117.md",
    "reports/stage2-v3-formal-contract-recheck-20261119/static-contract-review.md",
    "reports/stage2-v3-formal-contract-recheck-20261119/static-contract-review.json",
    "reports/stage2-v3-formal-contract-recheck-20261119/supplement-contract-verification.md",
    "data/schema/01_olist_schema.sql",
    "data/schema/03_extend_schema.sql",
    "src/main/java/com/text2sql/agent/generation/PromptTemplate.java",
    "src/main/resources/schema/metrics.yml",
    "data/eval/T3_time_window.yaml",
    "data/eval/T4_multi_join.yaml",
    "data/eval/T6_business_semantics.yaml",
    "data/benchmarks/olist-v3-candidate/reference-seal.json",
    "data/benchmarks/olist-v3-dev-runtime-candidate/reference-seal.json",
]

def digest(data):
    return hashlib.sha256(data).hexdigest()

def sha(text):
    return digest(text.encode("utf-8"))

def dump(name, obj):
    (OUT / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

def outer_words(sql):
    # A narrowly scoped lexical aid, never represented as an SQL semantic prover.
    result = []
    depth = 0
    quoted = False
    i = 0
    while i < len(sql):
        c = sql[i]
        if c == "'":
            if quoted and i + 1 < len(sql) and sql[i + 1] == "'":
                i += 2
                continue
            quoted = not quoted
        elif not quoted:
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            elif depth == 0 and (c.isalpha() or c == "_"):
                end = i + 1
                while end < len(sql) and (sql[end].isalnum() or sql[end] == "_"):
                    end += 1
                result.append((sql[i:end].upper(), i, end))
                i = end
                continue
        i += 1
    return result

def order_clause(sql):
    words = outer_words(sql)
    for i, (word, start, _) in enumerate(words[:-1]):
        if word == "ORDER" and words[i + 1][0] == "BY":
            end = next((p for w, p, _ in words[i + 2:] if w in ("LIMIT", "OFFSET", "FETCH")), len(sql))
            return sql[words[i + 1][2]:end].strip().rstrip(";")
    return None

def limit_value(sql):
    for word, _, end in outer_words(sql):
        if word == "LIMIT":
            found = re.match(r"\s*(\d+)", sql[end:])
            return int(found.group(1)) if found else "UNRESOLVED"
    return None

formal_time = set("T3-001 T3-003 T3-007 T3-009 T3-010 T3-012 T3-014 T3-021 T3-023 T3-027 T3-029 T3-030 T6-004 T6-022".split())
dev_time = set("T3-002 T3-006 T3-008 T3-011 T3-022 T3-026 T6-012 T6-025".split())
explicit_full = {"formal": {"T5-001"}, "development": {"T2-005", "T2-010"}}
limits_100 = set("T4-013 T4-019 T4-024 T4-028 T4-044".split())
null_order = {"T2-019": "budget", "T2-040": "total", "T3-018": "avg_days", "T4-033": "total"}

overrides = {
    ("formal", "T3-014"): {
        "question": "以全部订单最大下单日期为参考日，包含参考日的最后90个自然日内，仅对窗口中实际有关联订单明细事实的周，按下单时间统计每周商品销售额（不含运费）。不补没有明细事实的周；只累计窗口内明细，周以周一为起始日期，按周起始日期升序。",
        "reason": "原题不能唯一决定连续周历或事实周。V4显式选择事实周；不是宣称原题本来唯一如此。首尾不完整周只累计90日窗口内部事实。",
        "issues": ["WINDOW_WEEK_COVERAGE"],
        "counterexamples": ["C08_WEEK_GAP", "C09_PARTIAL_WEEK", "C10_EMPTY_ANCHOR"],
    },
    ("formal", "T3-027"): {
        "question": "仅按已知注册日期所在的自然月，统计每月会员注册数量；仅列实际有会员注册的月份，注册日期未知的会员不参加月分组，也不输出未知月份组。按月份升序。",
        "reason": "原题不能唯一裁决未知注册日期。V4明确排除未知日期，只排除当前这个时间指标的不可归月记录，不能推广为所有指标都排空。",
        "issues": ["UNKNOWN_REGISTRATION_MONTH"],
        "counterexamples": ["C11_UNKNOWN_REGISTRATION"],
    },
    ("formal", "T5-018"): {
        "question": "仅统计订单关联客户档案至少有一条默认收货地址（is_default=true）的订单，按默认地址州和该客户档案登记州组合统计不同订单数。同一客户有多个默认地址州时，订单分别计入各州组合；同州重复地址在同组只计一次，未知地址州保留NULL组；无默认地址的客户订单不参与。按订单数降序、地址州及登记州升序（NULL最后）取最多20组。各组不是全订单互斥分区，不得把组内订单数直接相加当全站订单数。",
        "reason": "原题未说明无默认与多默认情形。V4明确是默认地址关系分析，保留原INNER覆盖；不能冒充全订单唯一收货归属或实际下单时的地址快照。",
        "issues": ["DEFAULT_ADDRESS_POPULATION"],
        "counterexamples": ["C12_NO_DEFAULT", "C13_MULTIPLE_DEFAULT_STATES", "C14_DUPLICATE_DEFAULT_STATE", "C15_DEFAULT_NULL_STATE", "C16_FACT_RECORD_ADDRESS"],
    },
    ("formal", "T4-037"): {
        "question": "仅以至少出现一条订单明细的动销商品为候选，列出商品编号、图片数量和销量；销量按全部订单状态下的明细条数计算，按销量降序、商品编号升序取最多10个，不补没有明细的商品。",
        "reason": "原题“每个商品”与INNER候选覆盖不一致。V4显式选择动销候选；这是新请求范围，不是由当前商品全部动销推导的原题语义。",
        "issues": ["PRODUCT_CANDIDATE_COVERAGE"],
        "counterexamples": ["C17_PRODUCT_POPULATION"],
    },
    ("development", "T6-009"): {
        "replace": ("r.review_score <= 2", "r.review_score IN (1,2)"),
        "reason": "题面明确1到2星，<=2在DDL允许的0或负值上不等价。只改分子谓词，全部有效订单评价记录的分母、每评价等权、Top10与单位均不动。若另有经核验的1—5分数据域契约，可在第二次审查以该证据裁决；当前DDL没有CHECK约束。",
        "issues": ["EXPLICIT_RATING_RANGE"],
        "counterexamples": ["C20_EXPLICIT_SCORE_RANGE"],
    },
}

items = []
manifests = []
by_suite = {}
for suite, directory in SUITES.items():
    by_suite[suite] = {}
    for source in sorted(directory.glob("T*.yaml")):
        raw = source.read_bytes()
        manifests.append({"path": source.relative_to(ROOT).as_posix(), "sha256": digest(raw), "bytes": len(raw)})
        for entry in yaml.safe_load(raw.decode("utf-8")):
            by_suite[suite][entry["id"]] = entry
            original = entry["gold_sql"]
            proposed = original
            question = entry["question"]
            override = overrides.get((suite, entry["id"]), {})
            reasons = []
            issues = list(override.get("issues", []))
            examples = list(override.get("counterexamples", []))
            if suite == "formal" and entry["id"] in limits_100:
                proposed, n = re.subn(r"\s+LIMIT\s+100\s*;?\s*$", ";", proposed, flags=re.I)
                assert n == 1, entry["id"]
                reasons.append("去掉无请求LIMIT100，完整预期组集不截断；保留的ORDER BY仅是参考展示，不是评分约束。")
                issues.append("UNREQUESTED_LIMIT_100")
                examples += ["C01_UNREQUESTED_ORDER", "C02_UNREQUESTED_CUTOFF"]
            if suite == "formal" and entry["id"] in null_order:
                key = null_order[entry["id"]]
                proposed, n = re.subn(rf"ORDER BY {key} DESC\s*;", f"ORDER BY {key} DESC NULLS LAST;", proposed)
                assert n == 1, entry["id"]
                reasons.append("保留普通列表全部分组，在参考展示排序中显式NULLS LAST；不把未知值删掉，不把展示排序变成请求要求。")
                issues.append("NULL_PRESENTATION_ORDER")
                examples.append("C07_NULL_LIST_VS_EXTREMA")
            if "replace" in override:
                old, new = override["replace"]
                assert old in proposed
                proposed = proposed.replace(old, new)
            if override:
                question = override.get("question", question)
                reasons.append(override["reason"])
            requested_limit = limit_value(proposed)
            if suite == "formal" and entry["id"] in limits_100:
                requested_limit = None
            time_ids = formal_time if suite == "formal" else dev_time
            if requested_limit is not None:
                mode, origin = "TOTAL_ORDER_STRICT_TOP_N", "REQUEST_AND_PUBLISHED_TIE_DEFAULT"
            elif entry["id"] in time_ids:
                mode, origin = "TIME_ASC", "REQUEST_OR_PUBLISHED_TIME_DEFAULT"
            elif entry["id"] in explicit_full[suite]:
                mode, origin = "PARTIAL_ORDER", "EXPLICIT_REQUEST"
            else:
                mode, origin = "UNORDERED_MULTISET_OR_SINGLE_ROW", "NO_REQUESTED_ORDER"
            old_ordered = "ORDER BY" in original.upper() and "LIMIT" in original.upper()
            new_ordered = mode not in ("UNORDERED_MULTISET_OR_SINGLE_ROW",)
            scoring = {
                "mode": mode,
                "source": origin,
                "requestedLimit": requested_limit,
                "referenceOuterOrderKeys": order_clause(proposed) if new_ordered else None,
                "referenceOrderIsIncidental": bool(order_clause(proposed)) and not new_ordered,
                "topNTiePolicy": "EXACT_COMPLETE_IDENTITY_TUPLE_NO_BOUNDARY_SUBSTITUTION" if requested_limit is not None else None,
                "equalPrimaryKeysInFullList": "ALLOW_PERMUTATION_ONLY_WITHIN_EQUAL_REQUESTED_KEYS" if mode == "PARTIAL_ORDER" else None,
                "rowMultiplicity": "EXACT_MULTISET_NEVER_SET_DEDUPLICATION",
                "columnsUnitsNullsTolerance": "RETAIN_EXACT_CONTRACT_AND_FROZEN_TOLERANCE_NO_PIVOT_NO_SCALE_REPAIR",
                "truncation": "TRUNCATED_RESPONSE_CANNOT_PASS_COMPLETE_EXPECTATION",
                "sqlOrderLimitHeuristicProhibited": True,
                "currentHeuristicOrdered": old_ordered,
                "intendedOrderBehaviorDiffers": old_ordered != new_ordered,
                "counterexamples": ["C03_ORDERED_NO_LIMIT", "C04_STABLE_TOP_N", "C05_DISPLAY_PRECISION", "C06_HIDDEN_ORDER_KEY"] if new_ordered else ["C01_UNREQUESTED_ORDER"],
            }
            items.append({
                "suite": suite,
                "id": entry["id"],
                "difficulty": entry["difficulty"],
                "tags": entry.get("tags", []),
                "source": source.relative_to(ROOT).as_posix(),
                "sourceQuestionSha256": sha(entry["question"]),
                "sourceGoldSqlSha256": sha(original),
                "originalQuestion": entry["question"],
                "proposedQuestion": question,
                "originalGoldSql": original,
                "proposedGoldSql": proposed,
                "questionChange": question != entry["question"],
                "goldSqlChange": proposed != original,
                "scoringMetadataRequired": True,
                "reasons": reasons,
                "issueIds": issues,
                "counterexampleIds": examples,
                "scoring": scoring,
                "status": "PROPOSED_V4_CHANGE_REQUIRES_NEW_REFERENCE_REVIEW" if reasons else "NO_ADDITIONAL_CONTENT_CHANGE_FOUND_IN_THIS_STATIC_SCOPE",
                "isCleanBillOfHealth": False,
            })

assert len(items) == 200
assert len(by_suite["formal"]) == len(by_suite["development"]) == 100
assert not (set(by_suite["formal"]) & set(by_suite["development"]))

original_targets = {}
for filename in ("T3_time_window.yaml", "T4_multi_join.yaml", "T6_business_semantics.yaml"):
    source = ROOT / "data/eval" / filename
    # Only emit the three original public targets; no historical candidate output is read.
    for entry in yaml.safe_load(source.read_text(encoding="utf-8")):
        if entry["id"] in ("T4-001", "T3-002", "T6-003"):
            original_targets[entry["id"]] = entry

targets = []
for id_ in ("T4-001", "T3-002", "T6-003"):
    old, dev = original_targets[id_], by_suite["development"][id_]
    targets.append({
        "id": id_,
        "dimension": {"T4-001": "身份", "T3-002": "形状", "T6-003": "单位"}[id_],
        "originalQuestion": old["question"],
        "originalGoldSql": old["gold_sql"],
        "originalQuestionSha256": sha(old["question"]),
        "originalGoldSha256": sha(old["gold_sql"]),
        "devQuestionSha256": sha(dev["question"]),
        "devGoldSha256": sha(dev["gold_sql"]),
        "questionBytesEqual": old["question"] == dev["question"],
        "goldSqlBytesEqual": old["gold_sql"] == dev["gold_sql"],
        "recommendedConfirmation": {
            "T4-001": "ONE_SEPARATE_ORIGINAL_REQUEST_REQUIRED; V4 question explicitly changes identity/tie policy. Preserve old strict verdict even when disagreement is only old tie membership.",
            "T3-002": "REUSE_UNIQUE_FINAL_DEVELOPMENT_RESPONSE; execute original Gold and original strict shape/unit/order criterion separately, never copy the V4 verdict.",
            "T6-003": "QUESTION_UNCHANGED_BUT_GOLD_TEXT_CHANGED_NULLIF_AND_FORMAT; preregister reuse only if the ORIGINAL strict adjudicator/Gold is independently retained and applied to the unique response. If A protocol defines byte-identical Gold as mandatory, use ONE separate original request. Resolve this before freezing; never choose after observing the answer.",
        }[id_],
        "conflict": {
            "T4-001": "Old Gold has no stable boundary tie key; current product and V3 request require natural-person ID ASC. New contract cannot retroactively alter old strict score or make V3 success equal original target success.",
            "T3-002": "No substantive contract conflict. Two rows (year,count), integer year, chronological order; no pivot or extra columns.",
            "T6-003": "Registered repeat_rate is PERCENT, scale 2. Generic word 比例 does not override registered units. Old Gold lacks NULLIF, V3 adds it; empty-denominator boundary differs, so byte equality is false and must be disclosed.",
        }[id_],
        "responseSelection": "FINAL_SELECTED_CANDIDATE_UNIQUE_DEVELOPMENT_RESPONSE_OR_ONE_PREREGISTERED_ORIGINAL_RESPONSE; NEVER_FORMAL_BEST_OF_THREE",
        "strictOldCriterionPreserved": True,
    })

summary = {
    "identity": "Codex subagent /root/contract_audit, public contract review, 2026-11-19",
    "independence": {
        "noProductionTuningInThisAudit": True,
        "noPriorV3ReferenceAuthorshipInThisSession": True,
        "noPriorAuthorOrTunerRoleInThisSession": True,
        "historicalGlobalModelParticipationCannotBeAttested": True,
        "sharedFilesystemIsNotOperatingSystemIsolation": True,
        "currentCandidateOutputsRead": False,
        "blindHoldoutUnseenContentRead": False,
        "publicSealsIncidentallyContainHoldoutHashesOnly": True,
        "providerCalls": 0, "providerTokens": 0, "providerCostYuan": 0,
        "databaseCalls": 0, "javaReplays": 0, "gitCommits": 0,
        "productionDataHistoricalFileEdits": 0,
    },
    "status": "PUBLIC_CONTRACT_PROPOSAL_NOT_V4_FREEZE_ACCEPTANCE",
    "fullPublicReferenceStaticRead": {"formal": 100, "development": 100},
    "contentChanges": {suite: {"question": sum(x["questionChange"] for x in items if x["suite"] == suite), "goldSql": sum(x["goldSqlChange"] for x in items if x["suite"] == suite), "union": sum(x["questionChange"] or x["goldSqlChange"] for x in items if x["suite"] == suite)} for suite in SUITES},
    "orderMetadata": {suite: {"requiredItems": 100, "oldHeuristicFalseNegatives": [x["id"] for x in items if x["suite"] == suite and not x["scoring"]["currentHeuristicOrdered"] and x["scoring"]["mode"] != "UNORDERED_MULTISET_OR_SINGLE_ROW"], "oldHeuristicFalsePositives": [x["id"] for x in items if x["suite"] == suite and x["scoring"]["currentHeuristicOrdered"] and x["scoring"]["mode"] == "UNORDERED_MULTISET_OR_SINGLE_ROW"]} for suite in SUITES},
    "originalTargets": {"count": 3, "passThreshold": "at least 2/3 strictly", "separateOriginalT4RequestRequired": True, "T6ReuseRuleMustBePreregistered": True},
    "gradeGatesUnchanged": {"formalPercent": 89, "relativeRegressionLimitPp": 2, "devCorrect": 71, "devDeepCorrect": 4, "devBusinessCorrect": 6, "devTimeoutsMax": 9},
    "strataUnchanged": {suite: dict(Counter(x["difficulty"] for x in items if x["suite"] == suite)) for suite in SUITES},
    "validationBoundary": "Static public-source audit only. Proposed SQL is not DB-executed here. No model-quality gain, recovered score, causal accuracy, latency or real cost claim.",
}
for path in PUBLIC_DOCS:
    source = ROOT / path
    raw = source.read_bytes()
    manifests.append({"path": path, "sha256": digest(raw), "bytes": len(raw)})
dump("item-recommendations.json", {"schemaVersion": 1, "metadata": summary, "items": items})
dump("target-mapping.json", {"status": "PREREGISTRATION_RECOMMENDATION", "threshold": "at least 2/3 strictly", "targets": targets})
policies = []
for item in items:
    proposed = item["proposedGoldSql"]
    detail = item["scoring"]["mode"]
    if detail == "TOTAL_ORDER_STRICT_TOP_N":
        mode = "LEGACY_TOP_N"
        keys = []
    elif detail in ("TIME_ASC", "PARTIAL_ORDER"):
        mode = "ORDERED_ALL_ROWS"
        if detail == "TIME_ASC":
            keys = [{"columnIndex": 0, "direction": "ASC", "nulls": "LAST"}]
        else:
            column = 2 if item["suite"] == "formal" else 1
            keys = [{"columnIndex": column, "direction": "DESC", "nulls": "LAST"}]
    else:
        mode, keys = "UNORDERED_MULTISET", []
    split = "formal" if item["suite"] == "formal" else "dev"
    policies.append({
        "key": f"{split}:{item['id']}",
        "split": split,
        "id": item["id"],
        "difficulty": item["difficulty"],
        "mode": mode,
        "modeBasis": item["scoring"]["source"],
        "questionSha256": sha(item["proposedQuestion"]),
        "goldSqlSha256": sha(proposed),
        "referenceSource": item["source"],
        "orderKeys": keys,
        "allRowsRequired": mode != "LEGACY_TOP_N",
        "requestedLimit": item["scoring"]["requestedLimit"],
        "legacyComparatorReferenceSql": proposed if mode == "LEGACY_TOP_N" else None,
        "legacyTopNBoundary": "Reuse the frozen existing strict TopN comparator, reference SQL and actual SQL. Preserve current key mapping and tie semantics; no added boundary-tie waiver, no changed N, no candidate-chosen reference. Published stable complete-identity tie default remains the product contract; unresolved legacy limitations are reported separately." if mode == "LEGACY_TOP_N" else None,
        "orderedAllRowsAlgorithm": "First require exactly the same complete row multiset (including duplicate multiplicity, columns and frozen value tolerance); then require every adjacent pair to be nondecreasing under orderKeys. Equal requested-key blocks may permute; do not invent secondary keys. Compare full values independently of sort keys. Do not add LIMIT to activate ordering." if mode == "ORDERED_ALL_ROWS" else None,
        "unorderedAlgorithm": "Exact complete row multiset including duplicate multiplicity; preserve column count/order, frozen value normalization/tolerance and NULL; allow row permutation only." if mode == "UNORDERED_MULTISET" else None,
        "preserve": {"columnCountAndOrder": True, "allValues": True, "duplicateMultiplicity": True, "frozenNumericTolerance": True, "noUnitRepair": True, "noPivotRepair": True, "nullIsNotZero": True, "rejectTruncatedCompleteResponse": True},
        "sourceV3DefaultOrdered": item["scoring"]["currentHeuristicOrdered"],
        "proposedV4OldJarDefaultOrdered": "ORDER BY" in proposed.upper() and "LIMIT" in proposed.upper(),
        "sourceV3DefaultDifference": "OLD_DEFAULT_FALSE_ORDER_CONSTRAINT" if item["scoring"]["currentHeuristicOrdered"] and mode == "UNORDERED_MULTISET" else "OLD_DEFAULT_MISSES_REQUIRED_ORDER" if not item["scoring"]["currentHeuristicOrdered"] and mode == "ORDERED_ALL_ROWS" else "NO_ORDER_MODE_DIFFERENCE_IDENTIFIED",
        "proposedV4OldJarDefaultDifference": "OLD_DEFAULT_MISSES_REQUIRED_ORDER" if mode == "ORDERED_ALL_ROWS" else "NO_ORDER_MODE_DIFFERENCE_IDENTIFIED",
        "originalTargetCriterion": "This policy NEVER replaces the original frozen target adjudication. Reuse/extra-request decision is separately preregistered in target-mapping.json." if item["id"] in original_targets else None,
    })
assert len(policies) == len({p["key"] for p in policies}) == 200
for p in policies:
    assert p["mode"] in {"UNORDERED_MULTISET", "ORDERED_ALL_ROWS", "LEGACY_TOP_N"}
    assert p["mode"] != "ORDERED_ALL_ROWS" or (p["requestedLimit"] is None and p["orderKeys"])
dump("scoring-policy-proposal.json", {
    "schemaVersion": 1,
    "status": "PROPOSAL_NOT_EVALUATION_ADMITTED",
    "author": summary["identity"],
    "columnIndexBase": 0,
    "identityKey": "split:id",
    "admission": {
        "localAReviewCanValidateContractOnly": True,
        "controllerIntegrationImplementedByThisAudit": False,
        "pairedEvaluationMustUseSameExternalFrozenScorer": True,
        "mustNotTrustOldJarDefaultGradeOrUnknownMetadataHandling": True,
        "stateUntilControllerIntegrationAndFreeValidation": "NOT_EVALUATION_ADMITTED",
        "legacyOriginalThreeTargetCriteriaUntouched": True,
        "implementationOwner": "parent /root",
    },
    "counts": {split: dict(Counter(p["mode"] for p in policies if p["split"] == split)) for split in ("dev", "formal")},
    "oldJarDifferences": {"onProposedV4RequiredOrderMissed": [p["key"] for p in policies if p["proposedV4OldJarDefaultDifference"] == "OLD_DEFAULT_MISSES_REQUIRED_ORDER"], "onSourceV3UnrequestedOrderImposed": [p["key"] for p in policies if p["sourceV3DefaultDifference"] == "OLD_DEFAULT_FALSE_ORDER_CONSTRAINT"], "note": "Removing the five unrequested LIMIT100 already makes old-jar default grading unordered on those five proposed V4 Golds. Their false-order defect is a V3 finding, not a remaining V4 default-grade defect. The 25 requested no-LIMIT order checks remain missed on proposed V4 unless the external scorer is connected."},
    "minimumFreeChecks": ["C01_UNREQUESTED_ORDER", "C02_UNREQUESTED_CUTOFF", "C03_ORDERED_NO_LIMIT", "C04_STABLE_TOP_N", "C05_DISPLAY_PRECISION", "C06_HIDDEN_ORDER_KEY", "C07_NULL_LIST_VS_EXTREMA", "wrong column count/order", "changed full values", "lost duplicate row", "NULL versus zero", "unit and pivot not repaired", "both p9 and candidate external scorer paths used", "original three target criteria independently preserved"],
    "policies": policies,
})
dump("verification.json", {"summary": summary, "readOnlySourceManifest": manifests, "manifestSha256": sha(json.dumps(manifests, ensure_ascii=False, sort_keys=True)), "checks": {"publicItems": 200, "suiteSize100Each": True, "idOverlap": 0, "sourceContentNotWrittenByThisScript": True, "suggestedGoldMutationsHaveExactOneMatchAssertions": True}})
print(json.dumps(summary, ensure_ascii=False, indent=2))
