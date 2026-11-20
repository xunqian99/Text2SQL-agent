"""Seal this free package only; retain every original fixture on disk."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
BATCH = ROOT / "reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4"
GUIDE = ROOT / "docs/REPLAN_STAGE_D_CALLBACK_BUDGET_20261120.md"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


if __name__ == "__main__":
    assert subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode == 0
    assert not (BATCH / "human-paid-authorization.json").exists()
    registration = json.loads((OUT / "registration-verification.json").read_bytes())
    assert registration["planSha256"] == sha(BATCH / "plan.json")
    suite = json.loads((OUT / "final-suite-2/verification.json").read_bytes())
    assert suite["passed"] and suite["testsRun"] == 190
    assert all(sha(ROOT / relative) == identity for relative, identity in suite["sources"].items())
    archived = ("final-suite-1", "final-suite-2", "real-monitor-integration-1")
    archive_reports = []
    for name in archived:
        directory = OUT / name
        original = {p.relative_to(OUT).as_posix(): sha(p) for p in directory.rglob("*") if p.is_file()}
        archive = OUT / (name + "-evidence.zip")
        with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zipped:
            for relative in sorted(original):
                zipped.write(OUT / relative, relative)
        with zipfile.ZipFile(archive) as zipped:
            assert set(zipped.namelist()) == set(original)
            assert all(hashlib.sha256(zipped.read(relative)).hexdigest() == expected for relative, expected in original.items())
        report = OUT / (name + "-archive.json")
        write(report, dict(archive=archive.relative_to(ROOT).as_posix(), archiveSha256=sha(archive),
              archiveBytes=archive.stat().st_size, files=original, fileCount=len(original),
              originalFilesRetained=True, archiveRoundTripAllMatched=True, providerCalls=0, keyReads=0))
        archive_reports.append(report)
    paths = [p for p in OUT.iterdir() if p.is_file()]
    for name in archived:
        paths.extend(p for p in (OUT / name).rglob("verification.json") if p.is_file())
    paths.extend(p for p in BATCH.rglob("*") if p.is_file() and "tool-runtime" not in p.parts)
    paths.append(GUIDE)
    files = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(set(paths))}
    manifest = OUT / "delivery-manifest.json"
    write(manifest, dict(format="replan-d-revision4-root-free-package-v1", planSha256=sha(BATCH / "plan.json"),
          implementationCommit="495ebc3af03951e90df8a45786789421d861c9c3", files=files,
          combinedTests=190, priorSuiteTests=187, priorSuiteBelongsToPreviousHelper=True,
          providerCalls=0, actualHumanAuthorizationAbsent=True,
          status="FREE_COMPLETE_AWAITS_INDEPENDENT_ENGINEERING_REVIEW", stage2Accepted=False))
    paths.append(manifest)
    pathspec = OUT / ".git-pathspec"
    pathspec.write_bytes(b"".join(p.relative_to(ROOT).as_posix().encode() + b"\0" for p in sorted(set(paths))))
    try:
        subprocess.run(["git", "-c", "core.longpaths=true", "add", "--force", "--pathspec-from-file=" + str(pathspec),
                        "--pathspec-file-nul"], cwd=ROOT, check=True, capture_output=True)
    finally:
        pathspec.unlink()
    assert all(sha(ROOT / relative) == expected for relative, expected in files.items())
    print(json.dumps(dict(stagedFiles=len(set(paths)), manifestSha256=sha(manifest),
                          archiveCount=len(archived), planSha256=sha(BATCH / "plan.json"), providerCalls=0)))
