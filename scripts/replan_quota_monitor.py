"""Fail-closed observations of Java's mutable quota; never edits that quota.

The journal, checkpoint and controller/send-ledger anchors detect partial rollback
while another anchor survives. They are not an external signature: a coordinated
rewrite of this entire directory and every anchor is outside this local boundary.
Partial multi-file commits are deliberately unrecoverable, never auto-repaired.
"""
from __future__ import annotations

import copy
import base64
import hashlib
import json
import os
import re
from pathlib import Path
import threading
import time
from contextlib import contextmanager, nullcontext
from decimal import Decimal, InvalidOperation


ANCHOR = "candidateQuotaObservation"
FORMAT = "replan-candidate-quota-observation-v3"
POLICY = dict(format=FORMAT, identityFields=["version", "budgetId", "pricingIdentity"],
              decimalFields=["limitYuan", "reservationYuan", "knownCostYuan"],
              unknownNeverReleased=True, pendingRequiresSettlementEvidence=True,
              journalAppendOnly=True, fullJournalBytesRehashedBeforeDecodedCache=True,
              coordinatedWholeDirectoryRollbackResistance=False,
              partialCommitRecovery=False)


class QuotaRefused(RuntimeError):
    pass


def require(ok, reason):
    if not ok:
        raise QuotaRefused(reason)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def packed(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def value_sha(value):
    return digest(packed(value))


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON member in durable quota evidence")
        result[key] = value
    return result


def parse(raw, *, exact=False):
    return json.loads(raw, object_pairs_hook=unique, parse_float=Decimal if exact else float,
                      parse_constant=lambda _: (_ for _ in ()).throw(QuotaRefused("Non-finite JSON number")))


def read(path, *, exact=False):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), "Missing/linked durable quota evidence: " + path.name)
    try:
        return parse(path.read_bytes(), exact=exact)
    except (ValueError, OSError) as error:
        raise QuotaRefused("Unreadable quota evidence: " + path.name) from error


def decimal(value):
    require(type(value) in (int, str, Decimal), "Invalid quota decimal type")
    try:
        result = Decimal(value)
        require(result.is_finite() and result >= 0, "Negative/non-finite quota decimal")
        return result
    except InvalidOperation as error:
        raise QuotaRefused("Invalid quota decimal") from error


def money(value):
    return format(decimal(value).normalize(), "f")


def quota_semantic(quota):
    require(isinstance(quota, dict), "Quota is not an object")
    require(set(quota) == {"version", "budgetId", "pricingIdentity", "limitYuan", "reservationYuan",
                           "knownCostYuan", "knownCalls", "reservationBreached", "holds"}, "Quota fields drift")
    require(type(quota["knownCalls"]) is int and quota["knownCalls"] >= 0, "Invalid knownCalls")
    require(type(quota["reservationBreached"]) is bool, "Invalid reservationBreached")
    require(isinstance(quota["holds"], dict), "Invalid holds")
    result = copy.deepcopy(quota)
    for key in POLICY["decimalFields"]:
        result[key] = money(quota[key])
    for key in ("budgetId", "pricingIdentity"):
        require(isinstance(quota[key], str) and quota[key], "Missing quota identity")
    for hold_id, hold in result["holds"].items():
        require(isinstance(hold_id, str) and hold_id and isinstance(hold, dict)
                and set(hold) == {"requestId", "callNumber", "promptSha256", "status"}, "Invalid hold identity/content")
        require(isinstance(hold["requestId"], str) and bool(hold["requestId"])
                and type(hold["callNumber"]) is int and hold["callNumber"] > 0
                and isinstance(hold["promptSha256"], str) and len(hold["promptSha256"]) == 64
                and hold["status"] in ("PENDING", "UNKNOWN"), "Invalid hold content")
    return result


def validate_seed(seed, quota):
    initial, current = quota_semantic(seed), quota_semantic(quota)
    for key in ("version", "budgetId", "pricingIdentity", "limitYuan", "reservationYuan"):
        require(initial[key] == current[key], "Candidate quota identity drift: " + key)
    require(current["knownCalls"] >= initial["knownCalls"]
            and decimal(current["knownCostYuan"]) >= decimal(initial["knownCostYuan"]), "Candidate quota regressed below seed")
    require(not current["reservationBreached"], "Candidate quota reservation breached")
    require(all(current["holds"].get(k) == v for k, v in initial["holds"].items()), "Seed UNKNOWN removed/changed")
    require(decimal(current["knownCostYuan"]) + decimal(current["reservationYuan"]) * len(current["holds"])
            <= decimal(current["limitYuan"]), "Candidate quota exhausted")
    return current


def unanchored(value):
    result = copy.deepcopy(value)
    result.pop(ANCHOR, None)
    return result


def changes(before, after, prefix=()):
    """Only changed dictionary leaves enter the immutable append journal."""
    result = []
    for key in sorted(set(before) | set(after)):
        path = [*prefix, key]
        if key not in after:
            result.append(dict(path=path, removed=True))
        elif key not in before:
            result.append(dict(path=path, value=copy.deepcopy(after[key])))
        elif isinstance(before[key], dict) and isinstance(after[key], dict):
            result.extend(changes(before[key], after[key], tuple(path)))
        elif before[key] != after[key]:
            result.append(dict(path=path, value=copy.deepcopy(after[key])))
    return result


def apply_changes(value, delta):
    require(isinstance(delta, list), "Invalid quota knowledge delta")
    for change in delta:
        path = change.get("path")
        require(isinstance(path, list) and path and all(isinstance(p, str) for p in path), "Invalid delta path")
        target = value
        for part in path[:-1]:
            require(isinstance(target.get(part), dict), "Invalid delta parent/order")
            target = target[part]
        if change.get("removed") is True:
            require(set(change) == {"path", "removed"} and path[-1] in target, "Invalid delta removal")
            target.pop(path[-1])
        else:
            require(set(change) == {"path", "value"}, "Invalid delta value")
            target[path[-1]] = copy.deepcopy(change["value"])


def prompt_sha(payload):
    # Jackson default compact JSON for this frozen pipeline's two text messages.
    pairs = [[m["role"].upper(), m["content"]] for m in payload["messages"]]
    return digest(json.dumps(pairs, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


class QuotaMonitor:
    def __init__(self, batch, plan_sha, writer, owner_context=nullcontext):
        self.batch = Path(batch).resolve()
        self.plan_sha = plan_sha
        self.writer = writer
        self.owner_context = owner_context
        self.lock = threading.RLock()
        self.local = threading.local()
        self.poisoned = False
        self.journal = self.batch / "candidate-quota-observations.jsonl"
        self.head = self.batch / "candidate-quota-checkpoint.json"
        self.state = self.batch / "state.json"
        self.ledger = self.batch / "send-ledger.json"
        self.runtime = self.batch / "candidate-cost-quota.json"
        self.seed = self.batch / "candidate-quota-initial-snapshot.json"
        self.appender = self._append_bytes
        self._cache = None
        self._journal_hasher = None
        self.last_metrics = {}

    def _append_bytes(self, raw, expected_bytes, *, create=False):
        flags = os.O_WRONLY | os.O_APPEND | getattr(os, "O_BINARY", 0) | (os.O_CREAT | os.O_EXCL if create else 0)
        fd = os.open(self.journal, flags)
        try:
            require(os.fstat(fd).st_size == expected_bytes, "Journal append length changed")
            require(os.write(fd, raw) == len(raw), "Partial quota journal append; never repair")
            os.fsync(fd)
        finally:
            os.close(fd)

    @contextmanager
    def serial(self):
        with self.lock:
            require(not self.poisoned, "Quota observation persistence previously failed; no continuation")
            if getattr(self.local, "depth", 0):
                self.local.depth += 1
                try:
                    yield
                finally:
                    self.local.depth -= 1
                return
            with self.owner_context():
                path = self.batch / "candidate-quota-transaction.lock"
                try:
                    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                except FileExistsError as error:
                    raise QuotaRefused("Concurrent/crashed quota transaction; never auto-rebuild") from error
                self.local.depth = 1
                try:
                    os.write(fd, str(os.getpid()).encode("ascii")); os.fsync(fd)
                    yield
                finally:
                    self.local.depth = 0
                    os.close(fd)
                    path.unlink()

    def _capture_events(self):
        directory = self.batch / "send-ledger-events"
        captured = {}
        if directory.exists():
            require(directory.is_dir() and not directory.is_symlink(), "Invalid common event directory")
            for path in directory.iterdir():
                require(path.is_file() and not path.is_symlink(), "Linked/non-file common event")
                captured[path.name] = path.read_bytes()
        return captured

    def _send_events(self, ledger, captured):
        """Same immutable common event format; new anchors live elsewhere."""
        directory = self.batch / "send-ledger-events"
        expected = {}
        for call in ledger["calls"]:
            token = call["token"]
            permit = directory / (token + "-permit.json")
            admitted = {k: v for k, v in call.items() if k not in ("usageKnown", "costYuan", "result", "usage")}
            admitted.update(usageKnown=False, costYuan=None, result=None)
            require(permit.name in captured and parse(captured[permit.name]) == admitted, "Common permit content changed")
            expected[permit.name] = digest(captured[permit.name])
            if call.get("result") is not None:
                completed = directory / (token + "-completion.json")
                require(completed.name in captured and parse(captured[completed.name]) == call, "Common completion content changed")
                expected[completed.name] = digest(captured[completed.name])
                raw = directory / (token + "-upstream.body.bin")
                require(raw.name in captured
                        and call["result"].get("responseBodyFile") == raw.name
                        and digest(captured[raw.name]) == call["result"].get("responseBodySha256")
                        and len(captured[raw.name]) == call["result"].get("responseBytes"), "Common response bytes changed")
                expected[raw.name] = digest(captured[raw.name])
        for rejection in ledger["rejections"]:
            path = directory / (rejection["eventId"] + "-rejection.json")
            require(path.name in captured and parse(captured[path.name]) == rejection, "Common rejection content changed")
            expected[path.name] = digest(captured[path.name])
        actual = {p.name for p in directory.iterdir()} if directory.exists() else set()
        require(actual == set(captured) == set(expected), "Missing/orphan common send event")
        return expected

    def _load(self, *, changing_events=False):
        started = time.perf_counter()
        require(self.journal.is_file() and not self.journal.is_symlink(), "Missing quota observation journal")
        head = read(self.head)
        require(set(head) == {"planSha256", "sequence", "eventSha256", "journalBytes", "journalSha256"} and head["planSha256"] == self.plan_sha
                and type(head["sequence"]) is int and head["sequence"] >= 0, "Invalid quota checkpoint identity")
        journal_raw = self.journal.read_bytes()
        hasher = hashlib.sha256(journal_raw)
        require(type(head["journalBytes"]) is int and len(journal_raw) == head["journalBytes"]
                and hasher.hexdigest() == head["journalSha256"], "Quota journal truncated/changed or checkpoint rolled back")
        self._journal_hasher = hasher
        self.last_metrics["journalReadAndHashMs"] = round((time.perf_counter() - started) * 1000, 3)
        started = time.perf_counter()
        # Reuse decoded data only after cryptographically rechecking every current
        # journal byte. No metadata-only or cross-process cache is trusted.
        if self._cache is not None and self._cache[0] == head:
            latest = copy.deepcopy(self._cache[1])
        else:
            previous = None; latest = None; knowledge = {}; send_events = {}
            lines = journal_raw.splitlines(keepends=True)
            require(len(lines) == head["sequence"] + 1 and all(line.endswith(b"\n") for line in lines),
                    "Quota journal framing/sequence mismatch; no partial-tail repair")
            for i, line in enumerate(lines):
                event = parse(line)
                require(event.get("format") == FORMAT and event.get("planSha256") == self.plan_sha
                        and event.get("sequence") == i and event.get("previousSha256") == previous,
                        "Quota observation chain identity/order mismatch")
                try:
                    raw = base64.b64decode(event["runtimeRawBase64"], validate=True)
                except (ValueError, KeyError) as error:
                    raise QuotaRefused("Missing/invalid original quota snapshot bytes") from error
                require(digest(raw) == event["runtimeRawSha256"]
                        and quota_semantic(parse(raw, exact=True)) == event["quota"], "Quota source bytes/semantic snapshot mismatch")
                apply_changes(knowledge, event["knowledgeDelta"])
                require(isinstance(event["sendEventDelta"], dict) and not set(event["sendEventDelta"]) & set(send_events),
                        "Common immutable event delta duplicated/replaced")
                send_events.update(event["sendEventDelta"])
                previous = digest(line); latest = event
            require(previous == head["eventSha256"], "Quota checkpoint rolled back/changed")
            latest["knowledge"] = knowledge; latest["sendEvents"] = send_events
            self._cache = (copy.deepcopy(head), copy.deepcopy(latest))
        self.last_metrics["chainDecodeOrVerifiedCacheMs"] = round((time.perf_counter() - started) * 1000, 3)
        state, ledger = read(self.state), read(self.ledger)
        require(state.get(ANCHOR) == head and ledger.get(ANCHOR) == head, "State/ledger quota observation anchor mismatch")
        require(value_sha(unanchored(state)) == latest["stateSha256"]
                and value_sha(unanchored(ledger)) == latest["ledgerSha256"], "State/ledger changed outside quota transaction")
        send_events = latest["sendEvents"]
        started = time.perf_counter()
        captured = self._capture_events()
        self.last_metrics["commonRawReadMs"] = round((time.perf_counter() - started) * 1000, 3)
        started = time.perf_counter()
        for name, expected in send_events.items():
            require(name in captured and digest(captured[name]) == expected,
                    "Previously observed send event lost/changed")
        if not changing_events:
            require(self._send_events(ledger, captured) == send_events, "Send events/observation ordering mismatch")
        self.last_metrics["commonHashAndValidateMs"] = round((time.perf_counter() - started) * 1000, 3)
        require(read(self.batch / "candidate-quota-genesis-policy.json") == {**POLICY, "seedSha256": digest(self.seed.read_bytes())},
                "Quota genesis policy/seed changed")
        return head, latest, state, ledger, captured

    def _transition(self, old, quota, ledger, *, response=None, response_question=None):
        knowledge = copy.deepcopy(old)
        require(quota["knownCalls"] >= old["knownCalls"]
                and decimal(quota["knownCostYuan"]) >= decimal(old["knownCostYuan"]), "Latest known quota rolled back")
        for hold_id, hold in old["unknowns"].items():
            require(quota["holds"].get(hold_id) == hold, "Observed UNKNOWN removed/changed")
        calls = {c["token"]: c for c in ledger["calls"]}
        retired = []
        settlement_cost = Decimal(0)
        completed_count = 0
        for hold_id, tracked in old["pending"].items():
            hold = tracked["hold"]
            current = quota["holds"].get(hold_id)
            if current is not None:
                require({k: v for k, v in current.items() if k != "status"}
                        == {k: v for k, v in hold.items() if k != "status"}, "PENDING identity changed")
                continue
            token = old["bindings"].get(hold_id)
            call = calls.get(token)
            if call and call.get("usageKnown") is True and call.get("result") is not None:
                settlement_cost += decimal(call["costYuan"])
                completed_count += 1
                retired.append((hold_id, "KNOWN", token))
            else:
                budget = (response or {}).get("budget") or {}
                # A returned request identity plus the complete immutable proxy
                # event set is the unsent proof. No observed permit can be refunded.
                budget_rejection = ((response or {}).get("status"), budget.get("termination")) in (
                    ("REQUEST_TIMEOUT", "DEADLINE_EXCEEDED"), ("RESOURCE_LIMITED", "MODEL_CALL_LIMIT"))
                offpeak = ((response or {}).get("status") == "GENERATION_FAILED" and budget.get("termination") == "COMPLETE"
                           and re.fullmatch(r"DeepSeek评测仅允许空闲时段调用；本次未发送。原因=(?:PEAK_HOURS|PEAK_BOUNDARY_RESERVE)，最早接续=\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?\+08:00\[Asia/Shanghai\]",
                                            str((response or {}).get("message", ""))) is not None)
                unsent = (token is None and tracked.get("questionId") is not None
                          and response_question == tracked["questionId"] == old.get("activeQuestion")
                          and budget.get("requestId") == hold["requestId"]
                          and type(budget.get("modelCalls")) is int
                          and 0 <= budget["modelCalls"] <= 2
                          and ((budget["modelCalls"] < hold["callNumber"] and budget_rejection) or offpeak))
                require(unsent, "PENDING disappeared without per-identity completion/unsent evidence")
                retired.append((hold_id, "UNSENT", None))
        delta_calls = quota["knownCalls"] - old["knownCalls"]
        delta_cost = decimal(quota["knownCostYuan"]) - decimal(old["knownCostYuan"])
        # All observed pending completions must have exactly matching known deltas.
        # New known data first observed without a tracked pending remains charged,
        # never a reason to discard another hold or relax a future high-water mark.
        require(delta_calls >= completed_count and delta_cost >= settlement_cost,
                "PENDING completion lacks corresponding known usage/cost")
        if completed_count:
            require(delta_calls == completed_count and delta_cost == settlement_cost,
                    "Candidate/proxy per-call settlement differs; investigate pricing/usage")
        for hold_id, outcome, token in retired:
            knowledge["pending"].pop(hold_id)
            knowledge["retired"][hold_id] = dict(hold=old["pending"][hold_id]["hold"], outcome=outcome, token=token)
        for hold_id, hold in quota["holds"].items():
            require(hold_id not in knowledge["retired"], "Settled hold identity reused")
            if hold["status"] == "UNKNOWN":
                knowledge["unknowns"][hold_id] = copy.deepcopy(hold)
                knowledge["pending"].pop(hold_id, None)
            else:
                require(hold_id not in knowledge["unknowns"], "UNKNOWN downgraded to PENDING")
                knowledge["pending"].setdefault(hold_id, dict(hold=copy.deepcopy(hold), questionId=old.get("activeQuestion")))
        knowledge.update(knownCalls=quota["knownCalls"], knownCostYuan=quota["knownCostYuan"])
        return knowledge

    def _append(self, prior, raw, quota, knowledge, state, ledger, context, evidence, *, previous=None, captured=None):
        state, ledger = unanchored(state), unanchored(ledger)
        sequence = 0 if prior is None else prior["sequence"] + 1
        previous = previous or dict(knowledge={}, sendEvents={})
        events = self._send_events(ledger, captured if captured is not None else self._capture_events())
        require(all(events.get(k) == v for k, v in previous["sendEvents"].items()), "Common event history regressed")
        event = dict(format=FORMAT, planSha256=self.plan_sha, sequence=sequence,
                     previousSha256=None if prior is None else prior["eventSha256"], context=context,
                     runtimeRawSha256=digest(raw), runtimeRawBase64=base64.b64encode(raw).decode("ascii"),
                     quota=quota, knowledgeDelta=changes(previous["knowledge"], knowledge),
                     stateSha256=value_sha(state), ledgerSha256=value_sha(ledger),
                     sendEventDelta={k: v for k, v in events.items() if k not in previous["sendEvents"]}, evidence=evidence or {})
        event_raw = packed(event) + b"\n"
        append_started = time.perf_counter()
        try:
            previous_bytes = 0 if prior is None else prior["journalBytes"]
            self.appender(event_raw, previous_bytes, create=prior is None)
            hasher = hashlib.sha256() if prior is None else self._journal_hasher.copy()
            hasher.update(event_raw)
            head = dict(planSha256=self.plan_sha, sequence=sequence, eventSha256=digest(event_raw),
                        journalBytes=previous_bytes + len(event_raw), journalSha256=hasher.hexdigest())
            state[ANCHOR] = head; ledger[ANCHOR] = head
            self.writer(self.state, state, replace=True)
            self.writer(self.ledger, ledger, replace=True)
            self.writer(self.head, head, replace=prior is not None)
            snapshot = {**event, "knowledge": copy.deepcopy(knowledge), "sendEvents": events}
            self._cache = (copy.deepcopy(head), snapshot)
            self._journal_hasher = hasher
        except BaseException:
            self.poisoned = True
            raise
        self.last_metrics["appendAndAnchorPersistenceMs"] = round((time.perf_counter() - append_started) * 1000, 3)
        return quota

    def initialize(self):
        with self.serial():
            require(not self.journal.exists() and not self.head.exists(), "Never reinitialize quota observations")
            require(read(self.batch / "candidate-quota-genesis-policy.json") == {**POLICY, "seedSha256": digest(self.seed.read_bytes())},
                    "Initial quota policy not bound")
            raw = self.runtime.read_bytes()
            quota = validate_seed(read(self.seed, exact=True), parse(raw, exact=True))
            state, ledger = read(self.state), read(self.ledger)
            require(state.get("rounds") == {} and state.get("requestsStarted") == 0
                    and not ledger["calls"] and not ledger["rejections"] and not ledger["breaches"]
                    and raw == self.seed.read_bytes(), "Observation genesis requires pristine unpaid registration")
            knowledge = dict(knownCalls=quota["knownCalls"], knownCostYuan=quota["knownCostYuan"],
                             unknowns=copy.deepcopy(quota["holds"]), pending={}, bindings={}, retired={},
                             runtimeStarted=False, activeSide=None, activeQuestion=None)
            require(all(h["status"] == "UNKNOWN" for h in knowledge["unknowns"].values()), "Genesis seed must contain only UNKNOWN")
            self._append(None, raw, quota, knowledge, state, ledger, "GENESIS", None)

    def observe(self, context, *, state_value=None, ledger_value=None, response=None, update=None, evidence=None):
        with self.serial():
            head, latest, state, ledger, captured = self._load(changing_events=ledger_value is not None)
            if latest["knowledge"].get("stopReason"):
                require(context == "STATE_WRITE" and state_value is not None and ledger_value is None,
                        "Persisted quota observation stop; no rollback/recovery")
                return self._seal_interruption(head, latest, state, ledger, captured, state_value)
            if state_value is not None:
                candidate = unanchored(state_value)
                require(candidate.get("requestsStarted", -1) >= state["requestsStarted"]
                        and set(state["rounds"]).issubset(candidate.get("rounds", {}))
                        and (state.get("terminal") is None or candidate.get("terminal") == state["terminal"]), "State counters/rounds regressed")
                state = candidate
            if ledger_value is not None:
                candidate = unanchored(ledger_value)
                previous = {c["token"]: c for c in ledger["calls"]}
                newer = {c["token"]: c for c in candidate["calls"]}
                require(len(newer) == len(candidate["calls"]) and set(previous).issubset(newer), "Common calls regressed/duplicated")
                for token, call in previous.items():
                    unchanged = call if call.get("result") is not None else {k: v for k, v in call.items() if k not in ("usageKnown", "costYuan", "result", "usage")}
                    require(all(newer[token].get(k) == v for k, v in unchanged.items()), "Common call/settlement rolled back")
                for key in ("rejections", "breaches"):
                    require(candidate[key][:len(ledger[key])] == ledger[key], "Common stops regressed")
                ledger = candidate
            raw = self.runtime.read_bytes()
            quota = quota_semantic(parse(raw, exact=True))
            response_question = None
            if response is not None:
                require(context == "HTTP_RETURN" and isinstance(evidence, dict), "Settlement response requires original HTTP evidence")
                relative = evidence.get("httpEvidence")
                require(isinstance(relative, str), "Missing original HTTP evidence path")
                meta_path = (self.batch / relative).resolve()
                require(meta_path.is_relative_to(self.batch) and not meta_path.is_symlink(), "HTTP evidence outside current registration")
                raw_path = meta_path.with_suffix(".body.bin")
                meta = read(meta_path)
                require(raw_path.is_file() and not raw_path.is_symlink(), "Original HTTP body missing")
                raw_http = raw_path.read_bytes()
                require(digest(meta_path.read_bytes()) == evidence.get("httpEvidenceSha256")
                        and digest(raw_http) == evidence.get("responseBodySha256") == meta.get("responseBodySha256")
                        and meta.get("responseComplete") is True and len(raw_http) == meta.get("responseBytes")
                        and parse(raw_http) == response, "Returned HTTP evidence hash/body mismatch")
                response_question = evidence.get("questionId")
                require(response_question == meta_path.stem, "HTTP question identity/path mismatch")
            try:
                validate_seed(read(self.seed, exact=True), quota)
                knowledge = self._transition(latest["knowledge"], quota, ledger, response=response, response_question=response_question)
                if not knowledge["runtimeStarted"] and state.get("rounds") == {} and state.get("requestsStarted") == 0 and not ledger["calls"]:
                    require(raw == self.seed.read_bytes(), "Before real application start quota must equal exact seed")
                if update:
                    update(knowledge, quota)
            except QuotaRefused as error:
                # A validly parsed rejected snapshot still contains real observed
                # liabilities. Seal them before refusing; restoring runtime later
                # cannot erase the rejection or permit continuation.
                knowledge = copy.deepcopy(latest["knowledge"])
                knowledge["knownCalls"] = max(knowledge["knownCalls"], quota["knownCalls"])
                knowledge["knownCostYuan"] = money(max(decimal(knowledge["knownCostYuan"]), decimal(quota["knownCostYuan"])))
                variants = knowledge.setdefault("rejectedUnknownObservations", {})
                for identity, hold in quota["holds"].items():
                    if hold["status"] == "UNKNOWN":
                        variants[identity + ":" + value_sha(hold)] = copy.deepcopy(hold)
                        knowledge["unknowns"].setdefault(identity, copy.deepcopy(hold))
                knowledge["stopReason"] = str(error)
                self._append(head, raw, quota, knowledge, state, ledger, "QUOTA_STOP", dict(
                    rejectedContext=context, reason=str(error), sourceEvidence=evidence or {}), previous=latest, captured=captured)
                raise
            return self._append(head, raw, quota, knowledge, state, ledger, context, evidence,
                                previous=latest, captured=captured)

    def persist(self, path, value):
        path = Path(path).resolve()
        require(path in (self.state, self.ledger), "Only controller state/ledger may be anchored")
        return self.observe("STATE_WRITE" if path == self.state else "LEDGER_WRITE",
                            state_value=value if path == self.state else None,
                            ledger_value=value if path == self.ledger else None)

    def _seal_interruption(self, head, latest, state, ledger, captured, value):
        # A quota stop forbids further admission, but the frozen controller may
        # seal its already-running round. Retain the original observed liability
        # snapshot; do not read a replacement runtime or erase the stop reason.
        candidate, previous_state = unanchored(value), unanchored(state)
        require(previous_state.get("terminal") is None and candidate.get("terminal") == "INTERRUPTED_NO_REPLAY",
                "Quota stop only permits an irreversible interruption terminal")
        previous_rounds = previous_state.get("rounds", {})
        current_rounds = candidate.get("rounds", {})
        running = [name for name, record in previous_rounds.items() if record.get("status") == "RUNNING"]
        require(len(running) == 1 and set(current_rounds) == set(previous_rounds),
                "Interruption must retain exactly the existing running round")
        name = running[0]
        before, after = previous_rounds[name], current_rounds[name]
        additions = {"reason", "finishedAt", "completedAnswers"}
        require(after.get("status") == "INTERRUPTED"
                and set(after) == set(before) | additions
                and all(after.get(k) == v for k, v in before.items() if k != "status")
                and isinstance(after.get("reason"), str) and bool(after["reason"])
                and isinstance(after.get("finishedAt"), str) and bool(after["finishedAt"])
                and type(after.get("completedAnswers")) is int
                and 0 <= after["completedAnswers"] <= previous_state["requestsStarted"],
                "Invalid quota-stop interruption record")
        comparable = copy.deepcopy(candidate)
        comparable["terminal"] = previous_state["terminal"]
        comparable["rounds"][name] = copy.deepcopy(before)
        require(comparable == previous_state, "Interruption cannot change requests, other rounds or state identity")
        raw = base64.b64decode(latest["runtimeRawBase64"], validate=True)
        return self._append(head, raw, latest["quota"], latest["knowledge"], candidate, ledger,
                            "INTERRUPTED_AFTER_QUOTA_STOP", dict(round=name, stopReason=latest["knowledge"]["stopReason"]),
                            previous=latest, captured=captured)

    def before(self, payload, question_id, attempt):
        matched = []
        def match(knowledge, quota):
            fingerprint = prompt_sha(payload)
            matched.extend(k for k, h in quota["holds"].items() if h["status"] == "PENDING"
                           and h["callNumber"] == attempt and h["promptSha256"] == fingerprint
                           and k not in knowledge["bindings"])
            require(len(matched) <= 1, "Ambiguous candidate PENDING identity")
            require(knowledge["activeSide"] in ("B", "F"), "No observed real application owner")
            if knowledge["activeSide"] == "F":
                require(len(matched) == 1, "Candidate send has no unique durable PENDING")
            if matched:
                require(knowledge["pending"][matched[0]]["questionId"] == question_id,
                        "Pending/question provenance mismatch")
        self.observe("PROXY_BEFORE", update=match, evidence=dict(questionId=question_id, attempt=attempt, promptSha256=prompt_sha(payload)))
        return matched[0] if matched else None

    def bind(self, hold_id, permit):
        def bind(knowledge, quota):
            if hold_id is not None:
                require(hold_id in knowledge["pending"] and hold_id not in knowledge["bindings"], "Duplicate/missing pending binding")
                knowledge["bindings"][hold_id] = permit["token"]
        self.observe("PERMIT_BINDING", update=bind, evidence=dict(holdId=hold_id, token=permit["token"],
                     questionId=permit["questionId"], attempt=permit["attempt"], requestBodySha256=permit["requestBodySha256"]))

