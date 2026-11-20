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
FORMAT = "replan-candidate-quota-observation-v4"
POLICY = dict(format=FORMAT, identityFields=["version", "budgetId", "pricingIdentity"],
              decimalFields=["limitYuan", "reservationYuan", "knownCostYuan"],
              unknownNeverReleased=True, pendingRequiresSettlementEvidence=True,
              journalAppendOnly=True, fullJournalBytesRehashedBeforeDecodedCache=True,
              historicalCommonBytesChecked="before every question, round end and first owner/process use",
              transactionChecks="full journal SHA, current quota, anchors, ledger and exact new common bytes/directory set",
              sameNameHistoricalRawTamperDetectedAtNextFullCheck=True,
              fastCapability="in-memory same controller-owner instance, plan, round, question fence and current head",
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
    result = dict(pairs)
    require(len(result) == len(pairs), "Duplicate JSON member in durable quota evidence")
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
    # Every caller treats nested state/ledger as read-only. Transactions retain
    # the monitor->gate lock until validation/hash/persistence completes; only
    # this top-level anchor is changed. Knowledge/cache snapshots still copy.
    result = dict(value)
    result.pop(ANCHOR, None)
    return result


def working_knowledge(old):
    # Nested hold/fence records are immutable; transitions replace/add/remove
    # dictionary entries. Cache boundaries still take a full defensive copy.
    result = dict(old)
    for key in ("unknowns", "pending", "bindings", "retired", "permitFences", "rejectedUnknownObservations"):
        if key in old:
            result[key] = dict(old[key])
    return result


def changes(before, after, prefix=()):
    """Only changed dictionary leaves enter the immutable append journal."""
    if before == after:
        return []
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
    def __init__(self, batch, plan_sha, writer, owner_context=nullcontext, owner_identity=lambda: None):
        self.batch = Path(batch).resolve()
        self.plan_sha = plan_sha
        self.writer = writer
        self.owner_context = owner_context
        self.owner_identity = owner_identity
        self._round = None
        self._capability = None
        self.lock = threading.RLock()
        self.local = threading.local()
        self.poisoned = False
        self.journal = self.batch / "candidate-quota-observations.jsonl"
        self.head = self.batch / "candidate-quota-checkpoint.json"
        self.state = self.batch / "state.json"
        self.ledger = self.batch / "send-ledger.json"
        self.runtime = self.batch / "candidate-cost-quota.json"
        self.seed = self.batch / "candidate-quota-initial-snapshot.json"
        self.failure = self.batch / "candidate-quota-persistence-stop.json"
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

    def _event_names(self):
        directory = self.batch / "send-ledger-events"
        if not directory.exists():
            return set()
        require(directory.is_dir() and not directory.is_symlink(), "Invalid common event directory")
        names = set()
        with os.scandir(directory) as entries:
            for entry in entries:
                require(entry.is_file(follow_symlinks=False) and not entry.is_symlink(), "Linked/non-file common event")
                names.add(entry.name)
        return names

    def invalidate_owner(self, owner):
        with self.lock:
            if self._capability and self._capability["owner"] is owner:
                self._capability = None
                self._round = None

    def _grant(self, head, knowledge):
        owner = self.owner_identity()
        if owner is not None and self._round is not None:
            self._capability = dict(owner=owner, planSha256=self.plan_sha, round=self._round,
                                    fence=copy.deepcopy(knowledge.get("admittedRequest")), head=copy.deepcopy(head))

    def _require_capability(self, head, knowledge):
        cap = self._capability
        require(cap is not None and self.owner_identity() is not None and cap["owner"] is self.owner_identity()
                and cap["planSha256"] == self.plan_sha and cap["round"] == self._round
                and cap["head"] == head and cap["fence"] == knowledge.get("admittedRequest"),
                "Missing/stale full-check capability for this owner, plan, round or question fence")

    def open_round(self, round_name):
        require(self.owner_identity() is not None, "Real send gate requires a live controller owner")
        self._capability = None
        self._round = round_name
        return self.observe("SEND_GATE_OPEN", evidence=dict(round=round_name))

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

    def _load(self, *, changing_events=False, fast=False):
        started = time.perf_counter()
        require(not self.failure.exists() and not self.failure.is_symlink(), "Permanent quota persistence stop; never repair/recover")
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
                for snapshot in event.get("quotaSnapshots", []):
                    original = base64.b64decode(snapshot["rawBase64"], validate=True)
                    require(digest(original) == snapshot["rawSha256"] and quota_semantic(parse(original, exact=True)) == snapshot["quota"],
                            "Callback A/B quota source mismatch")
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
        if fast:
            self._require_capability(head, latest["knowledge"])
            require(not changing_events and self._event_names() == set(send_events), "Missing/orphan transaction common event")
            captured = None
        else:
            captured = self._capture_events()
        self.last_metrics["commonRawReadMs"] = round((time.perf_counter() - started) * 1000, 3)
        started = time.perf_counter()
        if not fast:
            for name, expected in send_events.items():
                require(name in captured and digest(captured[name]) == expected,
                        "Previously observed send event lost/changed")
            if not changing_events:
                require(self._send_events(ledger, captured) == send_events, "Send events/observation ordering mismatch")
        self.last_metrics["commonHashAndValidateMs"] = round((time.perf_counter() - started) * 1000, 3)
        require(read(self.batch / "candidate-quota-genesis-policy.json") == {**POLICY, "seedSha256": digest(self.seed.read_bytes())},
                "Quota genesis policy/seed changed")
        return head, latest, state, ledger, captured

    def _validate_ledger(self, previous, candidate):
        old = {c["token"]: c for c in previous["calls"]}
        new = {c["token"]: c for c in candidate["calls"]}
        require(len(new) == len(candidate["calls"]) and set(old).issubset(new), "Common calls regressed/duplicated")
        for token, call in old.items():
            fixed = call if call.get("result") is not None else {k: v for k, v in call.items() if k not in ("usageKnown", "costYuan", "result", "usage")}
            require(all(new[token].get(k) == v for k, v in fixed.items()), "Common call/settlement rolled back")
        for key in ("rejections", "breaches"):
            require(candidate[key][:len(previous[key])] == previous[key], "Common stops regressed")
        require({k: v for k, v in candidate.items() if k not in ("calls", "rejections", "breaches", ANCHOR)}
                == {k: v for k, v in previous.items() if k not in ("calls", "rejections", "breaches", ANCHOR)}, "Common ledger identity changed")

    def _new_events(self, previous_ledger, ledger, old_hashes):
        # The callback/observe entry already checked the candidate against the
        # anchored ledger; no mutation is possible between it and this scan.
        old_calls = {c["token"]: c for c in previous_ledger["calls"]}
        expected = {}
        raw_checks = {}
        for call in ledger["calls"]:
            token = call["token"]
            if token not in old_calls:
                admitted = {k: v for k, v in call.items() if k not in ("usageKnown", "costYuan", "result", "usage")}
                admitted.update(usageKnown=False, costYuan=None, result=None)
                expected[token + "-permit.json"] = admitted
            if call.get("result") is not None and (token not in old_calls or old_calls[token].get("result") is None):
                expected[token + "-completion.json"] = call
                raw_checks[token + "-upstream.body.bin"] = call["result"]
        for rejection in ledger["rejections"][len(previous_ledger["rejections"]):]:
            expected[rejection["eventId"] + "-rejection.json"] = rejection
        names = set(expected) | set(raw_checks)
        require(not names & set(old_hashes), "New common event replaces an anchored original")
        directory = self.batch / "send-ledger-events"
        hashes = dict(old_hashes)
        for name in names:
            raw = (directory / name).read_bytes()
            if name in expected:
                require(parse(raw) == expected[name], "New immutable common event content mismatch")
            else:
                result = raw_checks[name]
                require(result.get("responseBodyFile") == name and digest(raw) == result.get("responseBodySha256")
                        and len(raw) == result.get("responseBytes"), "New original upstream response mismatch")
            hashes[name] = digest(raw)
        require(self._event_names() == set(hashes), "Common directory changed during transaction")
        return hashes

    def _transition(self, old, quota, ledger, *, response=None, response_question=None):
        knowledge = working_knowledge(old)
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
                knowledge["pending"].setdefault(hold_id, dict(hold=copy.deepcopy(hold), questionId=old.get("activeQuestion"),
                                                             requestFence=copy.deepcopy(old.get("admittedRequest"))))
        knowledge.update(knownCalls=quota["knownCalls"], knownCostYuan=quota["knownCostYuan"])
        return knowledge

    def _append(self, prior, raw, quota, knowledge, state, ledger, context, evidence, *, previous=None, captured=None,
                fast=False, previous_ledger=None, snapshots=None):
        state, ledger = unanchored(state), unanchored(ledger)
        sequence = 0 if prior is None else prior["sequence"] + 1
        previous = previous or dict(knowledge={}, sendEvents={})
        events = (self._new_events(previous_ledger, ledger, previous["sendEvents"]) if fast
                  else self._send_events(ledger, captured if captured is not None else self._capture_events()))
        require(all(events.get(k) == v for k, v in previous["sendEvents"].items()), "Common event history regressed")
        event = dict(format=FORMAT, planSha256=self.plan_sha, sequence=sequence,
                     previousSha256=None if prior is None else prior["eventSha256"], context=context,
                     runtimeRawSha256=digest(raw), runtimeRawBase64=base64.b64encode(raw).decode("ascii"),
                     quota=quota, knowledgeDelta=changes(previous["knowledge"], knowledge),
                     stateSha256=value_sha(state), ledgerSha256=value_sha(ledger),
                     sendEventDelta={k: v for k, v in events.items() if k not in previous["sendEvents"]}, evidence=evidence or {})
        if snapshots is not None:
            event["quotaSnapshots"] = snapshots
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
            snapshot = {**copy.deepcopy(event), "knowledge": copy.deepcopy(knowledge), "sendEvents": events}
            self._cache = (copy.deepcopy(head), snapshot)
            self._journal_hasher = hasher
            self._grant(head, knowledge)
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

    def observe(self, context, *, state_value=None, ledger_value=None, response=None, update=None, evidence=None, fast=False):
        with self.serial():
            head, latest, state, ledger, captured = self._load(changing_events=ledger_value is not None, fast=fast)
            old_ledger = ledger
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
                self._validate_ledger(ledger, candidate)
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
                    rejectedContext=context, reason=str(error), sourceEvidence=evidence or {}), previous=latest, captured=captured,
                    fast=fast, previous_ledger=old_ledger)
                raise
            return self._append(head, raw, quota, knowledge, state, ledger, context, evidence,
                                previous=latest, captured=captured, fast=fast, previous_ledger=old_ledger)

    def admit(self, state, fence):
        def update(knowledge, quota):
            require(fence["planSha256"] == self.plan_sha and fence["round"] == self._round
                    and fence["ordinal"] == state["requestsStarted"] and knowledge.get("activeQuestion") is None,
                    "Question admission fence/current request mismatch")
            knowledge["admittedRequest"] = copy.deepcopy(fence)
            knowledge["activeQuestion"] = fence["questionId"]
        return self.observe("QUESTION_ADMISSION_STATE", state_value=state, update=update, evidence=dict(fence=fence), fast=True)

    def finish_http(self, fence, *, response, evidence):
        def close(knowledge, quota):
            require(fence == knowledge.get("admittedRequest"), "HTTP return belongs to a different question admission")
            knowledge["activeQuestion"] = None
        return self.observe("HTTP_RETURN" if response is not None else "HTTP_FAILURE", response=response,
                            evidence={**evidence, "requestFence": fence}, update=close, fast=True)

    def _snapshot(self):
        raw = self.runtime.read_bytes()
        try:
            quota = quota_semantic(parse(raw, exact=True))
        except BaseException as error:
            error.quota_runtime_raw = raw
            raise
        return raw, quota, dict(rawBase64=base64.b64encode(raw).decode("ascii"), rawSha256=digest(raw), quota=quota)

    def _fold(self, knowledge, quota, ledger):
        validate_seed(read(self.seed, exact=True), quota)
        return self._transition(knowledge, quota, ledger)

    def _match_pending(self, knowledge, quota, payload, fence, attempt):
        require(fence == knowledge.get("admittedRequest") and knowledge.get("activeQuestion") == fence["questionId"],
                "Callback request fence is not the active durable admission")
        fingerprint = prompt_sha(payload)
        matches = [identity for identity, hold in quota["holds"].items() if hold["status"] == "PENDING"
                   and hold["callNumber"] == attempt and hold["promptSha256"] == fingerprint
                   and identity not in knowledge["bindings"]]
        require(len(matches) <= 1 and knowledge.get("activeSide") in ("B", "F"), "Ambiguous pending or missing real application")
        if knowledge["activeSide"] == "F":
            require(len(matches) == 1, "Candidate send has no unique durable PENDING")
        if matches:
            tracked = knowledge["pending"][matches[0]]
            require(tracked["requestFence"] == fence and tracked["questionId"] == fence["questionId"], "PENDING provenance/fence mismatch")
        return matches[0] if matches else None

    def _stop_knowledge(self, prior, snapshots, reason):
        knowledge = working_knowledge(prior)
        for snapshot in snapshots:
            quota = snapshot["quota"]
            knowledge["knownCalls"] = max(knowledge["knownCalls"], quota["knownCalls"])
            knowledge["knownCostYuan"] = money(max(decimal(knowledge["knownCostYuan"]), decimal(quota["knownCostYuan"])))
            variants = knowledge.setdefault("rejectedUnknownObservations", {})
            for identity, hold in quota["holds"].items():
                if hold["status"] == "UNKNOWN":
                    variants[identity + ":" + value_sha(hold)] = copy.deepcopy(hold)
                    knowledge["unknowns"].setdefault(identity, copy.deepcopy(hold))
                elif identity not in knowledge["unknowns"] and identity not in knowledge["retired"]:
                    knowledge["pending"].setdefault(identity, dict(hold=copy.deepcopy(hold),
                        questionId=prior.get("activeQuestion"), requestFence=copy.deepcopy(prior.get("admittedRequest"))))
        knowledge["stopReason"] = reason
        return knowledge

    @contextmanager
    def callback(self, kind, gate_data, *, fence=None, payload=None, attempt=None, permit=None):
        """One full-byte checked transaction, A/B quota snapshots, one commit.

        The inherited gate writes immutable original files unchanged. Its save()
        is staged only in the owning callback; the proxy sees no permit until
        this context has durably committed the binding and every anchor.
        """
        require(kind in ("BEFORE", "AFTER"), "Invalid callback transaction kind")
        with self.serial():
            try:
                head, latest, state, ledger, captured = self._load(fast=True)
                require(not latest["knowledge"].get("stopReason"), "Persisted quota stop; callback refused")
                require(unanchored(gate_data) == unanchored(ledger), "In-memory common ledger differs from anchored current ledger")
            except BaseException as caught:
                self.poisoned = True
                self._record_callback_failure(kind, fence, [], caught)
                raise
            snapshots = []
            stage = dict(ledger=None, permit=permit)
            pending_id = None
            error = None
            raw = None; quota = None
            try:
                raw, quota, snapshot = self._snapshot(); snapshots.append(snapshot)
                knowledge = self._fold(latest["knowledge"], quota, ledger)
                if kind == "BEFORE":
                    pending_id = self._match_pending(knowledge, quota, payload, fence, attempt)
                else:
                    require(permit is not None and knowledge.get("permitFences", {}).get(permit["token"]) is not None,
                            "Completion has no durable original permit/question fence")
                    fence = knowledge["permitFences"][permit["token"]]
                try:
                    yield stage
                except BaseException as caught:
                    error = caught
                candidate = unanchored(stage["ledger"] if stage["ledger"] is not None else ledger)
                self._validate_ledger(ledger, candidate)
                raw, quota, snapshot = self._snapshot(); snapshots.append(snapshot)
                knowledge = self._fold(knowledge, quota, candidate)
                if error is not None:
                    raise error
                require(stage["ledger"] is not None, "Inherited callback did not stage its ledger")
                if kind == "BEFORE":
                    actual = stage["permit"]
                    require(actual is not None and actual["questionId"] == fence["questionId"] and actual["attempt"] == attempt,
                            "Private permit/question identity mismatch")
                    require(self._match_pending(knowledge, quota, payload, fence, attempt) == pending_id,
                            "PENDING changed between callback A and B")
                    if pending_id is not None:
                        knowledge["bindings"][pending_id] = actual["token"]
                    knowledge.setdefault("permitFences", {})[actual["token"]] = copy.deepcopy(fence)
                self._append(head, raw, quota, knowledge, state, candidate, "PROXY_" + kind + "_COMMIT",
                             dict(requestFence=fence, holdId=pending_id, token=(stage["permit"] or {}).get("token"),
                                  promptSha256=prompt_sha(payload) if payload is not None else None),
                             previous=latest, fast=True, previous_ledger=ledger, snapshots=snapshots)
            except BaseException as caught:
                # Corrupt journal/anchors fail before this block. Partial original
                # event writes or any failed commit are never repaired or retried.
                if not self.poisoned and snapshots and raw is not None:
                    candidate = unanchored(stage["ledger"] if stage["ledger"] is not None else ledger)
                    knowledge = self._stop_knowledge(latest["knowledge"], snapshots, type(caught).__name__ + ": " + str(caught))
                    try:
                        self._append(head, raw, quota, knowledge, state, candidate, "QUOTA_STOP",
                                     dict(rejectedContext="PROXY_" + kind, reason=str(caught), requestFence=fence,
                                          **self._invalid_snapshot_evidence(caught)),
                                     previous=latest, fast=True, previous_ledger=ledger, snapshots=snapshots)
                    except BaseException:
                        self.poisoned = True
                        self._record_callback_failure(kind, fence, snapshots, caught)
                        raise
                if not snapshots:
                    self.poisoned = True
                if self.poisoned:
                    self._record_callback_failure(kind, fence, snapshots, caught)
                raise

    @staticmethod
    def _invalid_snapshot_evidence(error):
        raw = getattr(error, "quota_runtime_raw", None)
        return (dict(invalidRuntimeRawBase64=base64.b64encode(raw).decode("ascii"), invalidRuntimeRawSha256=digest(raw))
                if isinstance(raw, bytes) else {})

    def _record_callback_failure(self, kind, fence, snapshots, error):
        # Original partial files/anchors remain untouched. This separate,
        # create-only stop preserves every valid A/B observation even when the
        # normal journal commit cannot pass its exact-directory checks.
        try:
            if not self.failure.exists():
                self.writer(self.failure, dict(format=FORMAT, planSha256=self.plan_sha,
                    rejectedContext="PROXY_" + kind, requestFence=fence,
                    reason=type(error).__name__ + ": " + str(error), quotaSnapshots=snapshots,
                    **self._invalid_snapshot_evidence(error)))
        except BaseException:
            # A completely unavailable disk cannot guarantee a further record.
            # Existing unmatched anchors/original files still forbid recovery.
            pass

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

