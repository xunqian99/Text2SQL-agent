"""Stage the existing frozen free manifest; never rebuild/overwrite evidence."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent


if __name__ == "__main__":
    manifest = OUT / "delivery-manifest.json"
    files = json.loads(manifest.read_bytes())["files"]
    assert all(hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() == expected for relative, expected in files.items())
    paths = [*files, manifest.relative_to(ROOT).as_posix(), Path(__file__).relative_to(ROOT).as_posix()]
    pathspec = OUT / ".git-pathspec-recovery"
    pathspec.write_bytes(b"".join(path.encode() + b"\0" for path in sorted(paths)))
    try:
        completed = subprocess.run(["git", "-c", "core.longpaths=true", "add", "--force",
                                   "--pathspec-from-file=" + str(pathspec), "--pathspec-file-nul"],
                                  cwd=ROOT, capture_output=True)
    finally:
        pathspec.unlink()
    result = dict(firstFreezeArchiveAndManifestCreated=True, firstStageExitCode=128,
                  firstStageCapturedStderrUnavailable=True,
                  recoveryUsesCommandLocalLongpaths=True, exitCode=completed.returncode,
                  stdout=completed.stdout.decode("utf-8", errors="replace"),
                  stderr=completed.stderr.decode("utf-8", errors="replace"),
                  originalManifestFilesAllUnchanged=True, providerCalls=0)
    result_path = OUT / "stage-recovery.json"
    with result_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({k: v for k, v in result.items() if k != "stderr"}))
    if completed.returncode:
        print(result["stderr"])
        raise SystemExit(completed.returncode)
    subprocess.run(["git", "-c", "core.longpaths=true", "add", "--force", "--", str(result_path)], cwd=ROOT, check=True)
