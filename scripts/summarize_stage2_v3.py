"""Read-only V3 aggregate audit. Never prints questions, SQL, sample IDs or failures."""
import argparse
import json
import sys

from run_stage2_v3_comparison import Refused, audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory")
    parser.add_argument("--evidence-only", action="store_true",
                        help="Recheck immutable archives without querying live database/current source.")
    args = parser.parse_args()
    try:
        result = audit(args.directory, live=not args.evidence_only)
    except Exception as error:
        # Report payloads and subprocess failures can contain private material; do not echo them.
        print(json.dumps(dict(refused=True, reason=str(error) if isinstance(error, Refused)
                              else type(error).__name__)), file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
