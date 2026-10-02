"""评估集公共工具：加载 YAML、执行 SQL、结果集规范化。

注意：这是**离线开发期工具**，不是运行时组件。
运行时的「生成 SQL vs 标准 SQL」比对由 Java 侧实现（阶段 1）。
这里存在的意义是：评估集在写完后必须先证明「每条 gold_sql 都能跑通」，
否则后面测出来的准确率是假的。
"""

from __future__ import annotations

import csv
import datetime as dt
import hashlib
import io
import json
import re
import subprocess
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
EVAL_DIR = REPO_ROOT / "data" / "eval"

CONTAINER = "text2sql-postgres"
DB_USER = "text2sql"
DB_NAME = "olist"

# 结果集规范化的浮点精度。ROADMAP 5.1 已标注这是执行准确率的已知坑：
# 浮点尾数、NULL 表示、行顺序都会造成误判，必须统一。
FLOAT_SCALE = Decimal("0.0001")
MAX_ROWS_FOR_EVAL = 500

# psql 默认把 NULL 输出成空串，和「空字符串值」无法区分，
# 会导致「单值 NULL 结果」被 csv 解析成一个空行后丢弃。
# 用一个显式哨兵把 NULL 变成可比较的字面量。
NULL_TOKEN = "@@NULL@@"


class SqlExecutionError(RuntimeError):
    """gold_sql 执行失败。失败即代表这条评估项无效，必须修掉。"""


def load_eval_items(paths: list[Path] | None = None) -> list[dict]:
    """按文件名顺序加载所有评估项，保持 YAML 中的原始顺序。"""
    files = paths if paths else sorted(EVAL_DIR.glob("T*.yaml"))
    items: list[dict] = []
    for f in files:
        raw = yaml.safe_load(f.read_text(encoding="utf-8"))
        if raw is None:
            continue
        for item in raw:
            item["_file"] = f.name
            items.append(item)
    return items


def run_sql(sql: str, *, timeout: int = 60) -> list[list[str | None]]:
    """在容器内用 psql 执行一条只读 SQL，返回 CSV 解析后的行。

    走 docker exec 而不是直连 5432，是为了避免引入数据库驱动依赖，
    也让「评估脚本」和「数据装载脚本」共用同一条执行通路。
    """
    proc = subprocess.run(
        [
            "docker", "exec", "-i", CONTAINER,
            "psql", "-U", DB_USER, "-d", DB_NAME,
            "-v", "ON_ERROR_STOP=1",
            "-P", f"null={NULL_TOKEN}",
            "--csv", "-f", "-",
        ],
        input=sql,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=timeout,
    )
    if proc.returncode != 0:
        raise SqlExecutionError(proc.stderr.strip())
    reader = csv.reader(io.StringIO(proc.stdout))
    # 只丢掉真正的空行（csv 在末尾可能产生），不要丢掉 [''] 这种单空列行。
    rows = [row for row in reader if row]
    return rows[1:] if rows else []  # 丢弃表头


def normalize_value(value: str | None) -> str:
    """把单元格统一成可比较的字符串。

    规则：NULL 统一成字面量 "NULL"；数字按 4 位小数四舍五入；
    DATE 与当天零点 timestamp 按同一展示值归一；其余去掉首尾空白。
    这样 15843553.24 与 15843553.2400001 视为相同，
    2016-09-01 与 2016-09-01 00:00:00 也视为相同。

    **本函数必须与 Java 侧 ResultNormalizer.normalizeValue 保持逐条一致**，
    否则会出现"离线校验说没问题、运行时判定失败"这种最难查的分叉。
    """
    if value is None or value == NULL_TOKEN:
        return "NULL"
    text = value.strip()
    try:
        dec = Decimal(text)
    except Exception:
        return _normalize_temporal(text)
    # 注意不要用 Decimal.normalize()，它会把 610 变成 6.1E+2，
    # 同一个数字两种写法会让执行准确率产生假失败。
    quantized = dec.quantize(FLOAT_SCALE, rounding=ROUND_HALF_UP)
    plain = format(quantized, "f")
    if "." in plain:
        plain = plain.rstrip("0").rstrip(".")
    return plain or "0"


# 时间写法：完整日期、日期+时间、可选小数秒。
# 月份/季度字符串不在这里展开，因为那会把有损展示强行解释成某个日期。
_DATE_RE = re.compile(
    r"(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?"
    r"(\.\d+)?"
)


def _normalize_temporal(text: str) -> str:
    """把同一时间点的不同写法归一成 'yyyy-MM-dd HH:mm:ss'。

    DATE 与当天零点 timestamp 只是展示类型不同，可以归一。
    2016-09、2016-Q3 这类有损字符串保持原样，不投影到人为约定的日期。

    非零小数秒保持原样，避免把不同时间点误判为相同。
    """
    m = _DATE_RE.fullmatch(text)
    if not m:
        return text
    year = int(m.group(1))
    month = int(m.group(2))
    day = int(m.group(3))
    hour = int(m.group(4)) if m.group(4) else 0
    minute = int(m.group(5)) if m.group(5) else 0
    second = int(m.group(6)) if m.group(6) else 0
    fraction = m.group(7)
    if hour > 23 or minute > 59 or second > 59:
        return text
    if fraction is not None and any(char != "0" for char in fraction[1:]):
        return text
    try:
        dt.date(year, month, day)
    except ValueError:
        return text
    return _canonical(year, month, day, hour, minute, second)


def _canonical(year: int, month: int, day: int, hour: int, minute: int, second: int) -> str:
    return f"{year:04d}-{month:02d}-{day:02d} {hour:02d}:{minute:02d}:{second:02d}"


def normalize_result(rows: list[list[str | None]], *, ordered: bool) -> list[list[str]]:
    """规范化结果集。

    ordered=True 时保留行序（gold_sql 里带 ORDER BY 且外层有 LIMIT 时，
    顺序本身就是答案的一部分）；否则按字典序排序，做多重集比较。
    """
    norm = [[normalize_value(c) for c in row] for row in rows]
    if not ordered:
        norm = sorted(norm)
    return norm


def result_digest(rows: list[list[str]]) -> str:
    """结果集指纹。用于快速判断两次执行是否一致。"""
    payload = json.dumps(rows, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]


def sql_order_matters(sql: str) -> bool:
    """粗判 gold_sql 的结果顺序是否承载语义。

    只有「ORDER BY + LIMIT」同时出现时顺序才真正决定答案集合，
    单纯 ORDER BY 用于展示，不影响集合比较。
    """
    upper = sql.upper()
    return "ORDER BY" in upper and "LIMIT" in upper


def is_degenerate(rows: list[list[str]]) -> bool:
    """判断结果集是否「退化」——即没有区分度。

    两类退化题在评估集里是毒药：答案恒为 0/NULL，或者干脆返回 0 行。
    这种题无论模型怎么答都测不出差异，必须替换。
    """
    if not rows:
        return True
    if len(rows) == 1 and len(rows[0]) == 1:
        cell = rows[0][0]
        if cell == "NULL":
            return True
        try:
            return Decimal(cell) == 0
        except Exception:
            return False
    return False
