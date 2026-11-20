"""Freeze only this free package; retain all scale fixtures on disk."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
BATCH = ROOT / "reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-3"
GUIDE = ROOT / "docs/REPLAN_STAGE_D_REREGISTRATION_20261119.md"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


if __name__ == "__main__":
    assert subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode == 0, "Index is not empty"
    assert not (BATCH / "human-paid-authorization.json").exists()
    verification = json.loads((OUT / "registration-verification.json").read_bytes())
    assert verification["planSha256"] == sha(BATCH / "plan.json")
    performance = OUT / "final-performance-1"
    original = {p.relative_to(OUT).as_posix(): sha(p) for p in performance.rglob("*") if p.is_file()}
    archive = OUT / "final-performance-1-evidence.zip"
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zipped:
        for relative in sorted(original):
            zipped.write(OUT / relative, relative)
    with zipfile.ZipFile(archive) as zipped:
        assert set(zipped.namelist()) == set(original)
        assert all(hashlib.sha256(zipped.read(relative)).hexdigest() == expected for relative, expected in original.items())
    write(OUT / "final-performance-archive.json", dict(
        scope="Same-source synthetic performance raw fixtures archived byte-for-byte; originals retained on disk",
        archive=archive.relative_to(ROOT).as_posix(), archiveSha256=sha(archive),
        archiveBytes=archive.stat().st_size, files=original,
        fileCount=len(original), originalFilesRetained=True, archiveRoundTripAllMatched=True,
        providerCalls=0, keyReads=0))
    paths = [p for p in OUT.rglob("*") if p.is_file() and not p.is_relative_to(performance)
             and "/temp/" not in p.as_posix() and "__pycache__" not in p.parts]
    paths.extend(p for p in BATCH.rglob("*") if p.is_file() and "tool-runtime" not in p.parts)
    paths.append(GUIDE)
    files = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(set(paths))}
    manifest = OUT / "delivery-manifest.json"
    write(manifest, dict(format="replan-d-revision3-root-free-package-v1", planSha256=sha(BATCH / "plan.json"),
          implementationCommit="b178a4b2fe454cf5b5df90e4f62de665fce1fe6c", files=files,
          combinedTests=166, providerCalls=0, actualHumanAuthorizationAbsent=True,
          status="FREE_COMPLETE_AWAITS_INDEPENDENT_ENGINEERING_REVIEW", stage2Accepted=False))
    paths.append(manifest)
    pathspec = OUT / ".git-pathspec"
    pathspec.write_bytes(b"".join(p.relative_to(ROOT).as_posix().encode() + b"\0" for p in sorted(set(paths))))
    try:
        subprocess.run(["git", "add", "--force", "--pathspec-from-file=" + str(pathspec), "--pathspec-file-nul"],
                       cwd=ROOT, check=True, capture_output=True)
    finally:
        pathspec.unlink()
    assert all(sha(ROOT / relative) == expected for relative, expected in files.items())
    print(json.dumps(dict(stagedFiles=len(set(paths)), manifestSha256=sha(manifest),
                          archiveFiles=len(original), archiveBytes=archive.stat().st_size,
                          planSha256=sha(BATCH / "plan.json"), providerCalls=0)))
