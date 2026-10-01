"""把评估集 YAML 重新格式化成人工可读的样式。

背景：早期版本的写回逻辑用 yaml.safe_dump 覆盖了原文件，语义没坏，
但把 tags 展开成多行、把 gold_sql 变成带转义符的单行字符串，还混入了
错误的 gold_result 字段（写的是行数，不是结果集）。人工审核口径时没法看。

这个脚本只负责「排版」：保留注释头，按固定字段顺序重排，
gold_sql 用 block literal（|），tags 用 flow style（[a, b]）。

用法：
    python scripts/format_eval_yaml.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import yaml  # noqa: E402

from eval_lib import EVAL_DIR  # noqa: E402

# 这些字段是写回时误加的，不属于评估集规范，格式化时丢掉。
DROP_KEYS = {"gold_result", "result_digest", "result_ordered"}
FIELD_ORDER = ["id", "difficulty", "tags", "question", "gold_sql", "notes"]


def _flow_list(values: list) -> str:
    return "[" + ", ".join(str(v) for v in values) + "]"


def _block_scalar(text: str) -> str:
    """gold_sql 用 block literal 输出，保持多行可读。"""
    body = text.rstrip("\n")
    lines = body.split("\n")
    return "|\n" + "\n".join("    " + ln if ln else "" for ln in lines)


def format_file(path: Path) -> None:
    original = path.read_text(encoding="utf-8")
    header: list[str] = []
    for line in original.splitlines():
        if line.startswith("- id:"):
            break
        header.append(line)
    while header and not header[-1].strip():
        header.pop()

    raw = yaml.safe_load(original) or []
    chunks: list[str] = []
    for item in raw:
        lines = [f"- id: {item['id']}"]
        lines.append(f"  difficulty: {item['difficulty']}")
        tags = item.get("tags") or []
        if tags:
            lines.append(f"  tags: {_flow_list(tags)}")
        lines.append(f"  question: {item['question']}")
        lines.append("  gold_sql: " + _block_scalar(item["gold_sql"]))
        notes = item.get("notes")
        if notes:
            lines.append(f"  notes: {str(notes).strip()}")
        chunks.append("\n".join(lines))

    path.write_text("\n".join(header) + "\n\n" + "\n\n".join(chunks) + "\n", encoding="utf-8")


def main() -> int:
    for path in sorted(EVAL_DIR.glob("T*.yaml")):
        format_file(path)
        print(f"formatted {path.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

