"""重新生成标准答案结果集。

设计取舍（面试可以讲）：
  标准答案结果集**不写回 YAML**，而是单独存到 data/eval/gold_results.json。
  原因：200 条题目里结果集最多 73 行，塞进 YAML 后文件会变成一堆带转义符的
  长字符串，人工审核口径时会非常痛苦。而「标准 SQL 必须人工审核」是
  ROADMAP 5.5 的硬要求，可读性不能牺牲。
  所以：YAML 保持人工可读（题面 + SQL + 口径说明），JSON 存机器比对用的快照。

用法：
    python scripts/refresh_gold_results.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from eval_lib import (  # noqa: E402
    EVAL_DIR,
    REPO_ROOT,
    load_eval_items,
    normalize_result,
    result_digest,
    run_sql,
    sql_order_matters,
)

OUT_PATH = EVAL_DIR / "gold_results.json"


def main() -> int:
    items = load_eval_items()
    payload: dict[str, dict] = {}
    for item in items:
        qid = item["id"]
        ordered = sql_order_matters(item["gold_sql"])
        rows = normalize_result(run_sql(item["gold_sql"]), ordered=ordered)
        payload[qid] = {
            "file": item["_file"],
            "difficulty": item["difficulty"],
            "ordered": ordered,
            "row_count": len(rows),
            "digest": result_digest(rows),
            "rows": rows,
        }

    OUT_PATH.write_text(
        json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    print(f"已写入 {OUT_PATH.relative_to(REPO_ROOT)}，共 {len(payload)} 条。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

