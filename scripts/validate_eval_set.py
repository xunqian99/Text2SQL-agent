"""评估集自检：每条 gold_sql 必须可执行，且结果集可被规范化。

这是评估集交付前的前置关卡。任何一条 SQL 跑不通，都说明这条评估项无效
（失败归因表里的「数据问题」），必须修掉再进入 baseline 测试。

只做校验，不改文件。要重新生成 gold_result 请跑 scripts/refresh_gold_results.py。

用法：
    python scripts/validate_eval_set.py
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from eval_lib import (  # noqa: E402
    MAX_ROWS_FOR_EVAL,
    SqlExecutionError,
    is_degenerate,
    load_eval_items,
    normalize_result,
    result_digest,
    run_sql,
    sql_order_matters,
)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 条，调试用")
    args = ap.parse_args()

    items = load_eval_items()
    if args.limit:
        items = items[: args.limit]

    ok, failed = [], []
    digests: dict[str, list[str]] = {}
    degenerate: list[tuple[str, str]] = []

    for item in items:
        qid = item["id"]
        sql = item["gold_sql"]
        try:
            raw_rows = run_sql(sql)
        except SqlExecutionError as exc:
            failed.append((qid, item["_file"], str(exc).splitlines()[0]))
            print(f"[FAIL] {qid} ({item['_file']}) {str(exc).splitlines()[0]}")
            continue

        ordered = sql_order_matters(sql)
        rows = normalize_result(raw_rows, ordered=ordered)
        if len(rows) > MAX_ROWS_FOR_EVAL:
            failed.append((qid, item["_file"], f"结果行数 {len(rows)} 超过上限 {MAX_ROWS_FOR_EVAL}"))
            print(f"[FAIL] {qid} 结果行数 {len(rows)} 超过上限")
            continue

        item["_rows"] = len(rows)
        item["_digest"] = result_digest(rows)
        item["_ordered"] = ordered
        if is_degenerate(rows):
            degenerate.append((qid, str(rows[:1])))
            print(f"[DEGEN] {qid} ({item['_file']}) 结果无区分度：{rows[:1]}")
        ok.append(item)
        digests.setdefault(item["_digest"], []).append(qid)

    print()
    print(f"可执行：{len(ok)}/{len(items)}")
    if failed:
        print(f"失败 {len(failed)} 条：")
        for qid, f, msg in failed:
            print(f"  - {qid} [{f}] {msg}")

    if degenerate:
        print(f"退化题 {len(degenerate)} 条（答案恒为 0/NULL 或空结果，必须替换）：")
        for qid, val in degenerate:
            print(f"  - {qid} -> {val}")

    dupes = {d: ids for d, ids in digests.items() if len(ids) > 1}
    if dupes:
        print(f"提示：{len(dupes)} 组评估项结果集完全相同（可能是重复题，也可能只是巧合）：")
        for d, ids in list(dupes.items())[:10]:
            print(f"  - {d}: {', '.join(ids)}")

    dist = Counter(i["difficulty"] for i in items)
    print("难度分布：")
    for k, v in dist.items():
        print(f"  - {k}: {v}")

    return 1 if (failed or degenerate) else 0


if __name__ == "__main__":
    raise SystemExit(main())

