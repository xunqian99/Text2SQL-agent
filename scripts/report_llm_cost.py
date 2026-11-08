#!/usr/bin/env python3
"""按逐条 JSONL 计算本项目 LLM 成本（不依赖控制台账单）。

背景：DeepSeek 控制台的用量/账单按账号汇总；如果同一个 API Key 还用于其他项目，
账单无法隔离。本项目每次调用都会写一行 JSONL（promptTokens / completionTokens，
以及 cacheHitTokens / cacheMissTokens），本脚本只读这些行，按给定单价计算，
因此结果只属于本项目。

计费口径（DeepSeek V4.1 Flash，2026-11-08 官方价，元/百万 token）：
  空闲时段：输入缓存未命中 1.0，输出 4.0；缓存命中 0.02
  高峰时段：输入缓存未命中 2.0，输出 8.0；缓存命中 0.04
高峰时段为北京时间周一至周五 9:00-12:00、14:00-18:00（不含法定节假日）。

如果 JSONL 里所有行都有 cacheMissTokens，按 hit/miss 分段计价（cache-aware）；
否则按「全部缓存未命中」计价（upper-bound），并明确标注。历史 2026-11-08 之前的
日志没有 cache 字段，所以是上限。

用法：
  python3 scripts/report_llm_cost.py --since 2026-11-08T20:50 --until 2026-11-08T21:10
  python3 scripts/report_llm_cost.py --input-price-per-1m 1.0 --output-price-per-1m 4.0 \
      --cache-hit-price-per-1m 0.02 --json-out reports/cost.json
"""

import argparse
import glob
import json
from collections import Counter


def load_rows(paths, since=None, until=None):
    rows = []
    for path in paths:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                row = json.loads(line)
                ts = row.get("timestamp") or ""
                if since and ts < since:
                    continue
                if until and ts >= until:
                    continue
                rows.append(row)
    return rows


def summarize(rows, input_price_per_1m, output_price_per_1m, cache_hit_price_per_1m):
    prompt = sum(row.get("promptTokens") or 0 for row in rows)
    completion = sum(row.get("completionTokens") or 0 for row in rows)
    total = sum(row.get("totalTokens") or 0 for row in rows)

    # 只有所有行都带 cache 明细时才按分段计价；否则退回上限口径。
    cache_aware = bool(rows) and all(row.get("cacheMissTokens") is not None for row in rows)
    if cache_aware:
        hit = sum(row.get("cacheHitTokens") or 0 for row in rows)
        miss = sum(row.get("cacheMissTokens") or 0 for row in rows)
        cost = (hit / 1e6 * cache_hit_price_per_1m
                + miss / 1e6 * input_price_per_1m
                + completion / 1e6 * output_price_per_1m)
        mode = "cache-aware"
        hit_rate = round(hit / (hit + miss), 4) if (hit + miss) else 0.0
    else:
        hit = 0
        miss = prompt
        cost = prompt / 1e6 * input_price_per_1m + completion / 1e6 * output_price_per_1m
        mode = "upper-bound"
        hit_rate = 0.0

    calls = len(rows)
    return {
        "calls": calls,
        "promptTokens": prompt,
        "completionTokens": completion,
        "totalTokens": total,
        "cacheHitTokens": hit,
        "cacheMissTokens": miss,
        "cacheHitRate": hit_rate,
        "costMode": mode,
        "costYuan": round(cost, 6),
        "perCallYuan": round(cost / calls, 6) if calls else 0.0,
        "status": dict(Counter(row.get("status") for row in rows)),
        "models": dict(Counter(row.get("model") for row in rows)),
    }


def main():
    parser = argparse.ArgumentParser(description="按 JSONL 计算本项目 LLM 成本")
    parser.add_argument("--glob", default="reports/llm-calls/*.jsonl",
                        help="JSONL 文件 glob，默认 reports/llm-calls/*.jsonl")
    parser.add_argument("--since", help="起始时间（ISO，含），如 2026-11-08T20:50")
    parser.add_argument("--until", help="结束时间（ISO，不含），如 2026-11-08T21:10")
    parser.add_argument("--input-price-per-1m", type=float, default=1.0,
                        help="输入缓存未命中单价（元/百万 token），默认 1.0")
    parser.add_argument("--output-price-per-1m", type=float, default=4.0,
                        help="输出单价（元/百万 token），默认 4.0")
    parser.add_argument("--cache-hit-price-per-1m", type=float, default=0.02,
                        help="输入缓存命中单价（元/百万 token），默认 0.02")
    parser.add_argument("--label", default="all", help="本次统计的标签")
    parser.add_argument("--json-out", help="把结果写到指定 JSON 文件")
    args = parser.parse_args()

    paths = sorted(glob.glob(args.glob))
    if not paths:
        raise SystemExit(f"没有匹配到 JSONL：{args.glob}")

    rows = load_rows(paths, args.since, args.until)
    result = summarize(rows, args.input_price_per_1m, args.output_price_per_1m,
                       args.cache_hit_price_per_1m)
    result.update({
        "label": args.label,
        "since": args.since,
        "until": args.until,
        "inputPricePer1m": args.input_price_per_1m,
        "outputPricePer1m": args.output_price_per_1m,
        "cacheHitPricePer1m": args.cache_hit_price_per_1m,
        "sourceFiles": paths,
        "note": "按 JSONL 逐条 token 计算；cache-aware 需要每行都有 cacheMissTokens，"
                "否则退回 upper-bound。",
    })

    text = json.dumps(result, ensure_ascii=False, indent=2)
    print(text)
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
        print(f"wrote {args.json_out}")


if __name__ == "__main__":
    main()
