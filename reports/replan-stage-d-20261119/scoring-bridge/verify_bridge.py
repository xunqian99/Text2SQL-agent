"""Free public-only bridge checks. Synthetic HTTP envelopes are never model-accuracy evidence."""
import copy
import hashlib
import json
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
RUN_NAME = sys.argv[1] if len(sys.argv) == 2 else "verification-initial"
if Path(RUN_NAME).name != RUN_NAME:
    raise ValueError("Verification evidence must stay directly inside the bridge evidence directory")
RUN = HERE / RUN_NAME
RUN.mkdir(exist_ok=False)
CP = ";".join((str(HERE / "classes"), "target/classes", "target/test-classes",
               (ROOT / "target/replan-b-classpath.txt").read_text().strip()))
checks = []


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.write("\n")


def command(case, args):
    proc = subprocess.run(["java", "-cp", CP, *args], cwd=ROOT, text=True,
                          encoding="utf-8", capture_output=True, timeout=90)
    write(RUN / f"{case}-process.json", dict(returncode=proc.returncode,
                                            stdout=proc.stdout, stderr=proc.stderr))
    return proc


def record(name, condition, **details):
    checks.append(dict(id=name, passed=bool(condition), **details))
    if not condition:
        write(RUN / "FAILED.json", dict(checks=checks, providerCalls=0))
        raise AssertionError(name)


prepared = {key: json.loads((HERE / f"prepared-{key}.json").read_text(encoding="utf-8"))
            for key in ("dev", "formal", "targets")}


def response(key):
    return dict(items=[dict(id=x["id"], httpStatus=200,
                            body=dict(status="SUCCESS", columns=x["gold"]["columns"],
                                      rows=x["gold"]["rows"], truncated=False))
                       for x in prepared[key]["items"]])


def run_score(name, key="dev", mutate=None, change_prepared=None,
              rejects=False, expected=None, raw_suffix=None):
    fixture = copy.deepcopy(response(key))
    p = copy.deepcopy(prepared[key])
    if mutate:
        mutate(fixture)
    if change_prepared:
        change_prepared(p)
        pp = RUN / f"{name}-prepared.json"
        write(pp, p)
    else:
        pp = HERE / f"prepared-{key}.json"
    rp, op = RUN / f"{name}-responses.json", RUN / f"{name}-score.json"
    if raw_suffix is None:
        write(rp, fixture)
    else:
        with rp.open("x", encoding="utf-8") as f:
            f.write(json.dumps(fixture, ensure_ascii=False) + raw_suffix)
    before = (sha(pp), sha(rp))
    proc = command(name, ["StageDScoringBridge", "score", str(pp), str(rp), str(op)])
    unchanged = before == (sha(pp), sha(rp))
    if rejects:
        record(name, proc.returncode != 0 and not op.exists() and unchanged,
               rejected=True, inputBytesUnchanged=unchanged)
        return
    if proc.returncode != 0:
        record(name, False, processError=proc.stderr)
    output = json.loads(op.read_text(encoding="utf-8"))
    record(name, output["total"] == len(p["items"]) and output["correct"] == expected and unchanged
           and output["responsesSha256"] == before[1] and output["preparedSha256"] == before[0]
           and sum(x["total"] for x in output["byLayer"].values()) == output["total"]
           and sum(x["correct"] for x in output["byLayer"].values()) == output["correct"],
           total=output["total"], correct=output["correct"], inputBytesUnchanged=unchanged)
    return output


frozen = ROOT / "reports/replan-stage-a-20261119/ReferenceContractScorer.java"
scorer_before = sha(frozen)
proc = command("frozen-scorer", ["ReferenceContractScorer", "selfcheck", str(RUN / "frozen-scorer.json")])
frozen_checks = json.loads((RUN / "frozen-scorer.json").read_text())
record("unchanged_frozen_scorer_boundaries", proc.returncode == 0 and frozen_checks["passed"] == 31
       and sha(frozen) == scorer_before, frozenChecks=31)

dev = run_score("dev_full_synthetic_gold_body", expected=100)
run_score("formal_full_synthetic_gold_body", "formal", expected=100)
run_score("original_targets_full_synthetic_gold_body", "targets", expected=2)
t3 = next(x for x in dev["items"] if x["id"] == "T3-002")
registration = json.loads((ROOT / "reports/replan-stage-a-20261119/original-target-preregistration.json").read_text(encoding="utf-8"))
original_t3 = next(x for x in registration["targets"] if x["id"] == "T3-002")
record("dev_t3_original_strict_identity_reuse", t3["originalTargetCorrect"]
       and t3["originalTargetQuestionSha256"] == original_t3["originalQuestionSha256"]
       and t3["originalTargetGoldSqlSha256"] == original_t3["originalGoldSqlSha256"])


def at(data, identity):
    return next(x for x in data["items"] if x["id"] == identity)


def body_set(identity, field, value):
    return lambda data: at(data, identity)["body"].__setitem__(field, value)


def body_edit(identity, edit):
    return lambda data: edit(at(data, identity)["body"])


t3id = "T3-002"
bad_http = run_score("http_500_cannot_pass_even_with_equal_body",
                    mutate=lambda d: at(d, t3id).__setitem__("httpStatus", 500), expected=99)
record("non200_t3_is_not_reusable", not at(bad_http, t3id)["originalTargetCorrect"])
run_score("non_success_cannot_pass_equal_rows", mutate=body_set(t3id, "status", "TIMEOUT"), expected=99)
run_score("truncation_cannot_pass_equal_rows", mutate=body_set(t3id, "truncated", True), expected=99)
run_score("wrong_result_not_reexecuted_from_attached_sql", "targets",
          mutate=body_edit("T6-003", lambda b: b.update(rows=[["999999999"]],
              sql=next(x["originalGoldSql"] for x in registration["targets"] if x["id"] == "T6-003"))), expected=1)
run_score("original_target_permutation_allowed", "targets",
          mutate=body_edit("T4-001", lambda b: b["rows"].reverse()), expected=2)
run_score("original_target_boundary_identity_substitution_rejected", "targets",
          mutate=body_edit("T4-001", lambda b: b["rows"][-1].__setitem__(0, "FORGED_BOUNDARY_ID")), expected=1)
run_score("original_target_unit_scaling_rejected", "targets",
          mutate=body_edit("T6-003", lambda b: b["rows"][0].__setitem__(0, str(Decimal(b["rows"][0][0]) / 100))), expected=1)


def item_matching(key, pred):
    return next(x for x in prepared[key]["items"] if pred(x))


unordered = item_matching("dev", lambda x: x["policy"]["mode"] == "UNORDERED_MULTISET_OR_SINGLE_ROW"
                          and len(x["gold"]["rows"]) > 1)
run_score("v4_unordered_permutation_allowed", mutate=body_edit(unordered["id"], lambda b: b["rows"].reverse()), expected=100)
run_score("v4_complete_multiset_no_row_drop", mutate=body_edit(unordered["id"], lambda b: b["rows"].pop()), expected=99)
top = item_matching("dev", lambda x: x["policy"]["mode"] == "TOTAL_ORDER_STRICT_TOP_N" and len(x["gold"]["rows"]) > 1)
run_score("v4_strict_topn_reverse_rejected", mutate=body_edit(top["id"], lambda b: b["rows"].reverse()), expected=99)
time = item_matching("dev", lambda x: x["policy"]["mode"] == "TIME_ASC" and len(x["gold"]["rows"]) > 1)
run_score("v4_time_reverse_rejected", mutate=body_edit(time["id"], lambda b: b["rows"].reverse()), expected=99)
partial = item_matching("formal", lambda x: x["policy"]["mode"] == "PARTIAL_ORDER"
                        and len({tuple(row[i] for i in x["policy"]["keyPositions"]) for row in x["gold"]["rows"]}) > 1)
run_score("v4_partial_key_reverse_rejected", "formal", mutate=body_edit(partial["id"], lambda b: b["rows"].reverse()), expected=99)
numeric = item_matching("dev", lambda x: x["policy"]["columnKinds"] == ["NUMERIC"] and len(x["gold"]["rows"]) == 1)
run_score("v4_frozen_numeric_tolerance", mutate=body_edit(numeric["id"], lambda b: b["rows"][0].__setitem__(0, b["rows"][0][0] + ".00000")), expected=100)
textual = item_matching("dev", lambda x: "TEXT" in x["policy"]["columnKinds"] and x["gold"]["rows"])
text_col = textual["policy"]["columnKinds"].index("TEXT")
run_score("v4_candidate_type_hint_cannot_coerce_identity", mutate=body_edit(textual["id"],
          lambda b: (b.update(columnKinds=["NUMERIC"] * len(b["columns"])),
                     b["rows"][0].__setitem__(text_col, "0" + b["rows"][0][text_col]))), expected=99)
null_item = item_matching("dev", lambda x: any(None in row for row in x["gold"]["rows"]))
null_row = next(i for i, row in enumerate(null_item["gold"]["rows"]) if None in row)
null_col = null_item["gold"]["rows"][null_row].index(None)
run_score("v4_null_is_not_literal_NULL", mutate=body_edit(null_item["id"],
          lambda b: b["rows"][null_row].__setitem__(null_col, "NULL")), expected=99)
run_score("v4_answer_wrong_width_is_incorrect", mutate=body_edit(t3id, lambda b: b["columns"].append("extra")), expected=99)
run_score("v4_no_rows_is_incorrect", mutate=body_set(t3id, "rows", []), expected=99)

run_score("missing_response_rejected", mutate=lambda d: d["items"].pop(), rejects=True)
run_score("extra_response_rejected", mutate=lambda d: d["items"].append(copy.deepcopy(d["items"][0])), rejects=True)
run_score("duplicate_response_id_rejected", mutate=lambda d: d["items"][1].update(id=d["items"][0]["id"]), rejects=True)
run_score("unknown_response_id_rejected", mutate=lambda d: d["items"][0].update(id="UNKNOWN"), rejects=True)
run_score("response_order_rejected", mutate=lambda d: d["items"].reverse(), rejects=True)
run_score("missing_truncated_rejected", mutate=body_edit(t3id, lambda b: b.pop("truncated")), rejects=True)
run_score("missing_columns_rejected", mutate=body_edit(t3id, lambda b: b.pop("columns")), rejects=True)
run_score("nonstring_cell_rejected", mutate=body_edit(t3id, lambda b: b["rows"][0].__setitem__(0, 2017)), rejects=True)
run_score("missing_success_body_rejected", mutate=lambda d: at(d, t3id).pop("body"), rejects=True)
run_score("noninteger_http_status_rejected", mutate=lambda d: at(d, t3id).update(httpStatus="200"), rejects=True)
run_score("prepared_policy_drift_rejected", change_prepared=lambda p: p["items"][0]["policy"].update(mode="PARTIAL_ORDER"), rejects=True)
run_score("prepared_gold_hash_drift_rejected", change_prepared=lambda p: p["items"][0].update(goldSqlSha256="0" * 64), rejects=True)
run_score("prepared_question_drift_rejected", change_prepared=lambda p: p["items"][0].update(question="改写题面"), rejects=True)
run_score("prepared_gold_truncation_rejected", change_prepared=lambda p: p["items"][0]["gold"].update(truncated=True), rejects=True)
run_score("prepared_subset_rejected", change_prepared=lambda p: p["items"].pop(), rejects=True)
run_score("arbitrary_dataset_key_rejected", change_prepared=lambda p: p.update(datasetKey="blind"), rejects=True)
run_score("trailing_json_rejected", raw_suffix="\n{}", rejects=True)

existing = RUN / "existing-output.json"
write(existing, dict(marker="retain exact original bytes"))
before = sha(existing)
proc = command("existing-output", ["StageDScoringBridge", "score", str(HERE / "prepared-dev.json"),
                                  str(RUN / "dev_full_synthetic_gold_body-responses.json"), str(existing)])
record("existing_output_never_overwritten", proc.returncode != 0 and sha(existing) == before)
proc = command("existing-prepare", ["StageDScoringBridge", "prepare", "dev", str(existing)])
record("existing_prepare_never_overwritten", proc.returncode != 0 and sha(existing) == before)
proc = command("forbidden-prepare-key", ["StageDScoringBridge", "prepare", "holdout", str(RUN / "forbidden-prepare.json")])
record("prepare_rejects_unseen_dataset_key", proc.returncode != 0 and not (RUN / "forbidden-prepare.json").exists())
record("frozen_scorer_source_bytes_unchanged", sha(frozen) == scorer_before)

write(RUN / "verification.json", dict(format="stage-d-free-public-scoring-bridge-verification-v1",
      checks=checks, bridgeChecks=len(checks), passed=sum(x["passed"] for x in checks),
      frozenScorerChecks=31, preparedPublicGoldCounts={k: len(v["items"]) for k, v in prepared.items()},
      preparedPublicGoldTotal=202, providerCalls=0, providerTokens=0, providerCostYuan=0,
      modelAccuracyMeasured=False, actualHttpRequests=0,
      fixtureMeaning="Synthetic saved HTTP envelopes only; responses are not model outputs.",
      sourceSha256=sha(ROOT / "scripts/replan/StageDScoringBridge.java"),
      frozenScorerSha256=scorer_before, preparedSha256={k: sha(HERE / f"prepared-{k}.json") for k in prepared}))
print(json.dumps(dict(passed=len(checks), total=len(checks), frozenScorerChecks=31,
                      publicGold=202, providerCalls=0)))
