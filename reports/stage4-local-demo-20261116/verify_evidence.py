"""Verify archived bytes and Git source identities; makes no model/database calls."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--revision", default="HEAD")
parser.add_argument("--staged", action="store_true")
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
manifest = json.loads(Path(__file__).with_name("manifest.json").read_text(encoding="utf-8-sig"))
for entry in manifest["artifacts"]:
    assert hashlib.sha256((root / entry["path"]).read_bytes()).hexdigest() == entry["sha256"], entry["path"]
for entry in manifest["sources"]:
    ref = (":" if args.staged else args.revision + ":") + entry["path"]
    data = subprocess.check_output(["git", "show", ref], cwd=root)
    assert hashlib.sha256(data).hexdigest() == entry["gitSha256"], ref
    if not args.staged:
        blob = subprocess.check_output(["git", "rev-parse", ref], cwd=root, text=True).strip()
        assert blob == entry["gitBlobSha1"], ref
print(f"Verified {len(manifest['artifacts'])} artifact bytes and {len(manifest['sources'])} Git sources.")
