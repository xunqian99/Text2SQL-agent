#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 docs/INTERVIEW_TEXT2SQL_2026.md 构建成单文件交互式 HTML。

用法: python scripts/build_interview_html.py
产物: docs/interview-2026.html（自包含，无外部依赖）
"""

import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "INTERVIEW_TEXT2SQL_2026.md"
OUT = ROOT / "docs" / "interview-2026.html"

DIFF_CLASS = {1: "d1", 2: "d2", 3: "d3"}


# ---------------------------------------------------------------- inline ----
def md_inline(text: str) -> str:
    """转义 + 行内标记：代码（按 CommonMark 的 N 反引号规则）、加粗、链接。"""
    text = html.escape(text, quote=False)

    # 1) 代码：扫描反引号 run，找等长的下一个 run 作为闭合
    out, i, n = [], 0, len(text)
    while i < n:
        if text[i] != "`":
            out.append(text[i])
            i += 1
            continue
        j = i
        while j < n and text[j] == "`":
            j += 1
        run = j - i
        # 找下一个长度恰为 run 的反引号串
        k = j
        close = -1
        while k < n:
            if text[k] == "`":
                m = k
                while m < n and text[m] == "`":
                    m += 1
                if m - k == run:
                    close = k
                    break
                k = m
            else:
                k += 1
        if close == -1:
            out.append(text[i:j])
            i = j
            continue
        code = text[j:close]
        if code.startswith(" ") and code.endswith(" ") and code.strip():
            code = code[1:-1]
        out.append("<code>%s</code>" % code)
        i = close + run
    text = "".join(out)

    # 2) 加粗、链接（代码已成型，内部不会再被匹配）
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2" target="_blank" rel="noopener">\1</a>', text)
    return text


# ----------------------------------------------------------------- blocks ---
def is_table_row(line: str) -> bool:
    s = line.strip()
    return s.startswith("|") and s.endswith("|") and s.count("|") >= 2


def render_table(rows):
    cells = [[c.strip() for c in r.strip().strip("|").split("|")] for r in rows]
    if len(cells) >= 2 and re.fullmatch(r"[-: ]+", cells[1][0] or "-"):
        head, body = cells[0], cells[2:]
    else:
        head, body = None, cells
    out = ['<div class="table-wrap"><table>']
    if head:
        out.append("<thead><tr>" + "".join("<th>%s</th>" % md_inline(c) for c in head) + "</tr></thead>")
    out.append("<tbody>")
    for r in body:
        out.append("<tr>" + "".join("<td>%s</td>" % md_inline(c) for c in r) + "</tr>")
    out.append("</tbody></table></div>")
    return "".join(out)


def render_list(items):
    ordered = bool(re.match(r"^\d+[.)]\s", items[0].strip()))
    tag = "ol" if ordered else "ul"
    out = ["<%s>" % tag]
    for it in items:
        s = re.sub(r"^\s*(?:[-*]|\d+[.)])\s+", "", it)
        out.append("<li>%s</li>" % md_inline(s))
    out.append("</%s>" % tag)
    return "".join(out)


def render_blocks(lines):
    """把若干原始行渲染成 HTML 片段（段落/列表/表格/引用）。"""
    out, buf, i = [], [], 0

    def flush():
        if buf:
            out.append("<p>%s</p>" % md_inline(" ".join(buf)))
            buf.clear()

    while i < len(lines):
        line = lines[i]
        s = line.strip()

        if not s or s == "---":
            flush()
            i += 1
            continue

        if is_table_row(line):
            flush()
            rows = []
            while i < len(lines) and is_table_row(lines[i]):
                rows.append(lines[i])
                i += 1
            out.append(render_table(rows))
            continue

        if re.match(r"^\s*(?:[-*]|\d+[.)])\s+", line) and not s.startswith("**"):
            flush()
            items = []
            while i < len(lines) and re.match(r"^\s*(?:[-*]|\d+[.)])\s+", lines[i]) and not lines[i].strip().startswith("**"):
                items.append(lines[i])
                i += 1
            out.append(render_list(items))
            continue

        if s.startswith(">"):
            flush()
            q = []
            while i < len(lines) and lines[i].strip().startswith(">"):
                q.append(lines[i].strip().lstrip(">").strip())
                i += 1
            out.append('<blockquote>%s</blockquote>' % md_inline(" ".join(q)))
            continue

        buf.append(s)
        i += 1

    flush()
    return "\n".join(out)


# ------------------------------------------------------------------ parse ---
PART_RE = re.compile(r"^##\s+(.+?)\s*$")
Q_RE = re.compile(r"^###\s+Q(\d+)\.(\d+)\s+(.+?)\s*$")
SUB_RE = re.compile(r"^###\s+(.+?)\s*$")
DIFF_RE = re.compile(r"★{1,3}\s*$")


def parse(md: str):
    lines = md.splitlines()
    parts, cur_part, cur_q = [], None, None
    preface = []
    skip = False          # 跳过「目录」这类非正文章节

    for raw in lines:
        line = raw.rstrip()

        m = PART_RE.match(line)
        if m:
            title = m.group(1)
            if not re.match(r"^(Part\s+\d+|附录)", title):
                skip = True
                cur_part = None
                cur_q = None
                continue
            skip = False
            cur_q = None
            cur_part = {"title": title, "anchor": "sec-%d" % len(parts),
                        "intro": [], "questions": [], "subs": []}
            parts.append(cur_part)
            continue

        if skip:
            continue

        m = Q_RE.match(line)
        if m and cur_part is not None:
            title = m.group(3)
            diff = 1
            dm = DIFF_RE.search(title)
            if dm:
                diff = dm.group(0).count("★")
                title = DIFF_RE.sub("", title).strip()
            cur_q = {
                "num": "Q%s.%s" % (m.group(1), m.group(2)),
                "anchor": "q%s-%s" % (m.group(1), m.group(2)),
                "part": len(parts) - 1,
                "title": title,
                "diff": diff,
                "lines": [],
            }
            cur_part["questions"].append(cur_q)
            continue

        m = SUB_RE.match(line)
        if m and cur_part is not None:
            cur_q = None
            cur_part["subs"].append({"title": m.group(1), "lines": []})
            continue

        if cur_part is None:
            preface.append(line)
            continue

        if cur_q is not None:
            cur_q["lines"].append(line)
        elif cur_part["subs"]:
            cur_part["subs"][-1]["lines"].append(line)
        else:
            cur_part["intro"].append(line)

    return parts, preface


# ---------------------------------------------------------------- render ----
def split_body(lines):
    """把题目正文拆成 考点 / 答案 / 追问对。"""
    blocks = []          # (kind, text)
    buf = []

    def flush():
        if buf:
            blocks.append(("p", " ".join(buf)))
            buf.clear()

    for line in lines:
        s = line.strip()
        if not s or s == "---":
            flush()
            blocks.append(("blank", ""))
            continue
        if s.startswith("**面试官想听**"):
            flush()
            blocks.append(("hint", re.sub(r"^\*\*面试官想听\*\*[：:]\s*", "", s)))
        elif s.startswith("**答**"):
            flush()
            blocks.append(("ans", re.sub(r"^\*\*答\*\*[：:]\s*", "", s)))
        elif s.startswith("**追问**"):
            flush()
            blocks.append(("fq", re.sub(r"^\*\*追问\*\*[：:]\s*", "", s)))
        elif s.startswith("**追问答**"):
            flush()
            blocks.append(("fa", re.sub(r"^\*\*追问答\*\*[：:]\s*", "", s)))
        elif s.startswith(">"):
            flush()
            blocks.append(("quote", s.lstrip(">").strip()))
        else:
            buf.append(s)
    flush()
    return blocks


def render_question(q, index):
    blocks = split_body(q["lines"])

    hint = [b for b in blocks if b[0] == "hint"]
    ans, fus = [], []
    state = "ans"

    def target():
        if state == "ans" or not fus:
            return ans
        return fus[-1]["a"]

    for kind, text in blocks:
        if kind == "hint":
            continue
        if kind == "fq":
            fus.append({"q": text, "a": []})
            state = "fq"
        elif kind == "fa":
            if fus:
                fus[-1]["a"].append(text)
            state = "fa"
        else:
            target().append(text)

    body = []
    if hint:
        body.append(
            '<div class="hint"><span class="hint-label">考点</span>%s</div>'
            % md_inline(" ".join(t for _, t in hint))
        )
    if ans:
        body.append('<div class="answer">%s</div>' % render_blocks(ans))
    for fu in fus:
        body.append(
            '<div class="fu"><div class="fu-q"><span class="fu-tag">追问</span>%s</div>'
            '<div class="fu-a">%s</div></div>'
            % (md_inline(fu["q"]), render_blocks(fu["a"]))
        )

    d = q["diff"]
    return (
        '<article class="q" id="{a}" data-part="{p}" data-diff="{d}" data-idx="{i}">'
        '<button class="q-head" type="button" aria-expanded="false">'
        '<span class="q-num">{n}</span>'
        '<span class="q-title">{t}</span>'
        '<span class="q-diff {dc}" title="难度 {dt}">{stars}</span>'
        '<span class="q-mark" role="checkbox" aria-checked="false" title="标记已掌握">'
        '<svg viewBox="0 0 20 20" width="14" height="14"><path d="M4 10.5l4 4 8-9" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>'
        "</span>"
        '<span class="q-chev" aria-hidden="true"></span>'
        "</button>"
        '<div class="q-body">{body}</div>'
        "</article>"
    ).format(
        a=q["anchor"], p=q["part"], d=d, i=index, n=q["num"],
        t=md_inline(q["title"]), dc=DIFF_CLASS[d], dt="★" * d, stars="★" * d,
        body="\n".join(body),
    )


def slug(title: str) -> str:
    m = re.match(r"Part\s+(\d+)", title)
    if m:
        return "part-%s" % m.group(1)
    return "appendix-%s" % re.sub(r"\D", "", title) or "appendix"


def build():
    md = SRC.read_text(encoding="utf-8")
    parts, preface = parse(md)

    total = sum(len(p["questions"]) for p in parts)
    idx = 0

    nav, main = [], []
    for p in parts:
        label = p["title"]
        n = len(p["questions"])
        nav.append(
            '<a class="nav-item" href="#{a}" data-target="{a}">'
            '<span class="nav-dot"></span>'
            '<span class="nav-label">{l}</span>'
            '<span class="nav-count">{n}</span>'
            "</a>".format(a=p["anchor"], l=html.escape(label), n=n if n else "")
        )

        sec = ['<section class="part" id="%s">' % p["anchor"]]
        sec.append('<div class="part-head"><h2>%s</h2><span class="part-meta">%s</span></div>'
                   % (md_inline(p["title"]), ("%d 题" % n) if n else ""))
        if p["intro"]:
            sec.append('<div class="part-intro">%s</div>' % render_blocks(p["intro"]))
        for q in p["questions"]:
            sec.append(render_question(q, idx))
            idx += 1
        for s in p["subs"]:
            sec.append('<div class="sub-block"><h3 class="sub-title">%s</h3>%s</div>'
                       % (md_inline(s["title"]), render_blocks(s["lines"])))
        sec.append("</section>")
        main.append("\n".join(sec))

    css = CSS
    js = JS.replace("__TOTAL__", str(total))
    hero = HERO.format(total=total, parts=len(parts))

    doc = (
        "<!DOCTYPE html>\n<html lang=\"zh-CN\" data-theme=\"light\">\n<head>\n"
        '<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        "<title>Text2SQL Agent 面试题库（2026）</title>\n"
        "<style>\n" + css + "\n</style>\n</head>\n<body>\n"
        + TOPBAR.format(total=total)
        + '<div class="shell">\n<aside class="sidebar" id="sidebar">\n'
        '<div class="side-head">目录</div>\n<nav class="nav">\n' + "\n".join(nav) + "\n</nav>\n"
        + '<div class="side-foot">共 %d 题 · %d 部分</div>\n</aside>\n' % (total, len(parts))
        + '<main class="main">\n' + hero + "\n" + "\n".join(main) + "\n</main>\n</div>\n"
        + '<button class="to-top" id="toTop" title="回到顶部" aria-label="回到顶部">'
        + '<svg viewBox="0 0 20 20" width="17" height="17" aria-hidden="true"><path d="M10 15V6M5.5 10L10 5.5 14.5 10" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/></svg>'
        + "</button>\n"
        + "<script>\n" + js + "\n</script>\n</body>\n</html>\n"
    )
    OUT.write_text(doc, encoding="utf-8")
    return total, len(doc)


CSS = r"""
*,*::before,*::after{box-sizing:border-box}
:root{
  --bg:#f5f6f8; --card:#ffffff; --text:#1b1f26; --muted:#6b7280; --faint:#9ca3af;
  --line:#e6e8ec; --line-soft:#eef0f3;
  --accent:#4f46e5; --accent-2:#6366f1; --accent-soft:#eef0ff; --accent-line:#c7cbfb;
  --code-bg:#f1f3f6; --code-fg:#334155;
  --hint-bg:#fff8e8; --hint-line:#f2c76b;
  --fu-bg:#f7f9ff; --fu-line:#c9d4f5;
  --d1:#0ea472; --d2:#c2810a; --d3:#dc2626;
  --shadow:0 1px 2px rgba(16,24,40,.05),0 2px 8px rgba(16,24,40,.06);
  --radius:12px;
}
html[data-theme="dark"]{
  --bg:#0e1015; --card:#161a21; --text:#e7e9ee; --muted:#98a1b2; --faint:#6b7280;
  --line:#242a34; --line-soft:#1c2129;
  --accent:#8b93ff; --accent-2:#a5b4fc; --accent-soft:#1d2130; --accent-line:#3a4160;
  --code-bg:#1e222b; --code-fg:#cfd6e4;
  --hint-bg:#241f12; --hint-line:#6b5622;
  --fu-bg:#151a26; --fu-line:#2c3752;
  --d1:#34d399; --d2:#fbbf24; --d3:#f87171;
  --shadow:0 1px 2px rgba(0,0,0,.4),0 2px 10px rgba(0,0,0,.3);
}
html{scroll-behavior:smooth}
body{
  margin:0;background:var(--bg);color:var(--text);
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
  font-size:15px;line-height:1.75;-webkit-font-smoothing:antialiased;
}
code{
  font-family:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace;
  font-size:.88em;background:var(--code-bg);color:var(--code-fg);
  padding:.1em .38em;border-radius:5px;word-break:break-word;
}
strong{font-weight:650;color:var(--text)}
a{color:var(--accent);text-decoration:none;border-bottom:1px solid var(--accent-line)}
a:hover{border-bottom-color:var(--accent)}

/* ---------------- top bar ---------------- */
.topbar{
  position:sticky;top:0;z-index:40;background:color-mix(in srgb,var(--card) 88%,transparent);
  backdrop-filter:saturate(180%) blur(12px);
  border-bottom:1px solid var(--line);
}
.topbar-in{
  max-width:1400px;margin:0 auto;padding:10px 20px;
  display:flex;align-items:center;gap:14px;flex-wrap:wrap;
}
.brand{display:flex;align-items:baseline;gap:9px;font-weight:700;font-size:15px;white-space:nowrap}
.brand small{font-weight:500;color:var(--muted);font-size:12px}
.search{
  flex:1 1 240px;min-width:180px;display:flex;align-items:center;gap:8px;
  background:var(--bg);border:1px solid var(--line);border-radius:9px;padding:6px 11px;
  transition:border-color .15s,box-shadow .15s;
}
.search:focus-within{border-color:var(--accent);box-shadow:0 0 0 3px var(--accent-soft)}
.search input{
  border:0;background:transparent;outline:0;color:var(--text);width:100%;
  font-size:14px;font-family:inherit;
}
.search kbd{
  font-family:inherit;font-size:11px;color:var(--faint);border:1px solid var(--line);
  border-radius:4px;padding:0 5px;background:var(--card);
}
.chips{display:flex;gap:6px;flex-wrap:wrap}
.chip{
  border:1px solid var(--line);background:var(--card);color:var(--muted);
  font-size:12.5px;font-family:inherit;padding:5px 11px;border-radius:999px;cursor:pointer;
  transition:.15s;white-space:nowrap;
}
.chip:hover{border-color:var(--accent-line);color:var(--text)}
.chip.on{background:var(--accent-soft);border-color:var(--accent-line);color:var(--accent);font-weight:600}
.icon-btn{
  border:1px solid var(--line);background:var(--card);color:var(--muted);
  width:32px;height:32px;border-radius:8px;cursor:pointer;font-size:14px;line-height:1;
  display:grid;place-items:center;transition:.15s;font-family:inherit;
}
.icon-btn:hover{color:var(--accent);border-color:var(--accent-line);background:var(--accent-soft)}
.search .ico{flex:none;color:var(--faint)}
.search:focus-within .ico{color:var(--accent)}
.search .hits{
  flex:none;font-size:11.5px;color:var(--accent);background:var(--accent-soft);
  border-radius:999px;padding:1px 8px;font-variant-numeric:tabular-nums;white-space:nowrap;
}
.search .hits[hidden]{display:none}
.icon-btn svg{display:block}
.progress-wrap{display:flex;align-items:center;gap:8px;font-size:12px;color:var(--muted);white-space:nowrap}
.progress-bar{width:74px;height:5px;border-radius:999px;background:var(--line);overflow:hidden}
.progress-bar i{display:block;height:100%;width:0;background:var(--accent);transition:width .3s}

/* ---------------- shell ---------------- */
.shell{max-width:1400px;margin:0 auto;display:grid;grid-template-columns:264px minmax(0,1fr);gap:28px;padding:0 20px}
.sidebar{
  position:sticky;top:64px;align-self:start;max-height:calc(100vh - 84px);
  overflow-y:auto;padding:18px 0 24px;scrollbar-width:thin;
}
.side-head{font-size:11px;letter-spacing:.14em;text-transform:uppercase;color:var(--faint);margin:0 0 10px 10px;font-weight:600}
.nav{display:flex;flex-direction:column;gap:1px}
.nav-item{
  display:flex;align-items:center;gap:8px;padding:6px 10px;border-radius:7px;
  color:var(--muted);font-size:13px;border:0;transition:.13s;
}
.nav-item:hover{background:var(--line-soft);color:var(--text)}
.nav-item.active{background:var(--accent-soft);color:var(--accent);font-weight:600}
.nav-dot{width:4px;height:4px;border-radius:50%;background:currentColor;opacity:.45;flex:none}
.nav-item.done .nav-dot{background:var(--d1);opacity:1;box-shadow:0 0 0 3px color-mix(in srgb,var(--d1) 18%,transparent)}
.nav-label{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.nav-count{font-size:11px;color:var(--faint);font-variant-numeric:tabular-nums}
.side-foot{margin:16px 0 0 10px;font-size:11px;color:var(--faint)}

.main{min-width:0;padding:0 0 120px}

/* ---------------- hero ---------------- */
.hero{padding:34px 0 26px;border-bottom:1px solid var(--line);margin-bottom:8px}
.hero h1{font-size:27px;line-height:1.35;margin:0 0 10px;letter-spacing:-.01em}
.hero p{color:var(--muted);margin:0 0 6px;font-size:14px;max-width:74ch}
.hero .stats{display:flex;gap:10px;flex-wrap:wrap;margin-top:18px}
.stat{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:9px 14px;box-shadow:var(--shadow)}
.stat b{display:block;font-size:18px;line-height:1.2;font-variant-numeric:tabular-nums}
.stat span{font-size:11.5px;color:var(--muted)}

/* ---------------- part ---------------- */
.part{padding-top:34px;scroll-margin-top:70px}
.part-head{display:flex;align-items:baseline;gap:12px;padding-bottom:10px;border-bottom:2px solid var(--line);margin-bottom:16px}
.part-head h2{font-size:19px;margin:0;letter-spacing:-.01em}
.part-meta{font-size:12px;color:var(--faint);font-variant-numeric:tabular-nums}
.part-intro{color:var(--muted);font-size:14px;margin:0 0 16px}
.part-intro strong,.part-intro table,.part-intro table strong{color:var(--text)}
.part-intro blockquote{
  margin:0;padding:10px 14px;border-left:3px solid var(--accent-line);
  background:var(--accent-soft);border-radius:0 8px 8px 0;color:var(--text);
}

/* ---------------- question ---------------- */
.q{
  background:var(--card);border:1px solid var(--line);border-radius:var(--radius);
  margin-bottom:10px;box-shadow:var(--shadow);overflow:hidden;
  transition:border-color .15s,box-shadow .15s;
}
.q:hover{border-color:var(--accent-line)}
.q.open{border-color:var(--accent-line)}
.q.done{opacity:.62}
.q.done .q-title{text-decoration:line-through;text-decoration-color:var(--faint);text-decoration-thickness:1px}
.q-head{
  width:100%;display:flex;align-items:center;gap:11px;padding:13px 15px;
  background:none;border:0;cursor:pointer;text-align:left;font-family:inherit;
  color:inherit;font-size:15px;
}
.q-head:focus-visible{outline:2px solid var(--accent);outline-offset:-2px}
.q-num{
  font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:11.5px;
  color:var(--accent);background:var(--accent-soft);border-radius:6px;
  padding:2px 7px;flex:none;font-weight:600;letter-spacing:.02em;
}
.q-title{flex:1;font-weight:550;line-height:1.5;min-width:0}
.q-diff{font-size:11px;letter-spacing:.06em;flex:none;font-weight:700}
.q-diff.d1{color:var(--d1)} .q-diff.d2{color:var(--d2)} .q-diff.d3{color:var(--d3)}
.q-mark{
  flex:none;width:22px;height:22px;border-radius:6px;border:1.5px solid var(--line);
  display:grid;place-items:center;color:transparent;transition:.15s;cursor:pointer;
}
.q-mark:hover{border-color:var(--d1);color:color-mix(in srgb,var(--d1) 45%,transparent)}
.q.done .q-mark{background:var(--d1);border-color:var(--d1);color:#fff}
.q-chev{
  flex:none;width:8px;height:8px;border-right:1.8px solid var(--faint);border-bottom:1.8px solid var(--faint);
  transform:rotate(45deg);margin:0 3px 3px 2px;transition:transform .2s;
}
.q.open .q-chev{transform:rotate(225deg);margin-bottom:-3px}
.q-body{display:none;padding:2px 15px 16px 15px;border-top:1px dashed var(--line-soft)}
.q.open .q-body{display:block;animation:fade .18s ease}
@keyframes fade{from{opacity:0;transform:translateY(-3px)}to{opacity:1;transform:none}}

.hint{
  display:flex;gap:9px;align-items:flex-start;margin:14px 0;
  background:var(--hint-bg);border-left:3px solid var(--hint-line);
  border-radius:0 8px 8px 0;padding:9px 13px;font-size:13.5px;color:var(--text);
}
.hint-label{
  flex:none;font-size:11px;font-weight:700;color:var(--d2);
  letter-spacing:.08em;padding-top:2px;
}
.answer{font-size:14.5px;color:var(--text)}
.answer p{margin:0 0 11px}
.answer p:last-child{margin-bottom:0}
.answer blockquote{
  margin:0 0 11px;padding:10px 14px;border-left:3px solid var(--accent-line);
  background:var(--accent-soft);border-radius:0 8px 8px 0;
}
.fu{
  margin-top:13px;background:var(--fu-bg);border:1px solid var(--fu-line);
  border-radius:9px;padding:11px 13px;font-size:13.5px;
}
.fu-q{font-weight:600;margin-bottom:6px;display:flex;gap:8px;align-items:baseline}
.fu-tag{
  flex:none;font-size:10.5px;font-weight:700;color:var(--accent);
  background:var(--card);border:1px solid var(--fu-line);border-radius:4px;padding:0 5px;
  letter-spacing:.06em;
}
.fu-a{color:var(--muted)}
.fu-a p{margin:0}

.table-wrap{overflow-x:auto;margin:12px 0;border:1px solid var(--line);border-radius:9px}
table{border-collapse:collapse;width:100%;min-width:720px;font-size:13px}
th,td{padding:8px 11px;text-align:left;border-bottom:1px solid var(--line-soft);vertical-align:top}
th{background:var(--bg);font-weight:650;font-size:12px;color:var(--muted);white-space:nowrap}
tr:last-child td{border-bottom:0}
tbody tr:hover{background:var(--line-soft)}
th:first-child,td:first-child{white-space:nowrap;font-variant-numeric:tabular-nums}
td code{white-space:normal;overflow-wrap:anywhere}

.sub-block{padding-top:22px}
.sub-title{font-size:16px;margin:0 0 10px;padding-left:10px;border-left:3px solid var(--accent)}
.sub-block ul,.sub-block ol{margin:0 0 12px;padding-left:22px;font-size:14px;color:var(--text)}
.sub-block li{margin-bottom:5px}

.empty{
  display:none;padding:60px 20px;text-align:center;color:var(--faint);font-size:14px;
}
.empty.on{display:block}

.to-top{
  position:fixed;right:22px;bottom:22px;width:38px;height:38px;border-radius:50%;
  border:1px solid var(--line);background:var(--card);color:var(--muted);
  cursor:pointer;box-shadow:var(--shadow);opacity:0;pointer-events:none;
  transition:.2s;z-index:30;display:grid;place-items:center;
}
.to-top.on{opacity:1;pointer-events:auto}
.to-top:hover{color:var(--accent);border-color:var(--accent-line)}

/* ---------------- responsive ---------------- */
@media (max-width:900px){
  .shell{grid-template-columns:1fr;gap:0}
  .sidebar{display:none}
  .hero h1{font-size:22px}
  .q-head{padding:12px 13px;gap:8px}
  .q-body{padding:2px 13px 14px}
  .q-diff{display:none}
}
@media (max-width:560px){
  .topbar-in{padding:8px 12px;gap:8px}
  .brand{font-size:14px}
  .brand small{display:none}
  .search{order:3;flex:1 1 100%;min-width:0}
  .search kbd{display:none}
  .chips{order:2;flex:1 1 auto;flex-wrap:nowrap;overflow-x:auto;scrollbar-width:none}
  .chips::-webkit-scrollbar{display:none}
  .chip{flex:none}
  .progress-wrap{display:none}
  .shell{padding:0 12px}
  .hero{padding:22px 0 18px}
  .hero h1{font-size:19px}
  .hero p{font-size:13px}
  .stat{padding:7px 11px}
  .stat b{font-size:16px}
  .part-head h2{font-size:16.5px}
  .q-title{font-size:14px}
  .answer{font-size:13.8px}
}
@media print{
  .topbar,.sidebar,.to-top,.q-mark,.q-chev{display:none!important}
  .shell{display:block;padding:0}
  .q{break-inside:avoid;box-shadow:none}
  .q-body{display:block!important;border-top:0}
  body{background:#fff;font-size:11pt}
}
"""

TOPBAR = """
<header class="topbar">
  <div class="topbar-in">
    <div class="brand">Text2SQL 面试题库 <small>2026 · {total} 题</small></div>
    <label class="search">
      <svg viewBox="0 0 20 20" width="15" height="15" aria-hidden="true" class="ico"><circle cx="9" cy="9" r="6" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M13.5 13.5L17 17" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
      <input id="search" type="search" placeholder="搜题目 / 关键词，如 Join、缓存、T5" autocomplete="off">
      <span class="hits" id="hits" hidden></span>
      <kbd>/</kbd>
    </label>
    <div class="chips" id="chips">
      <button class="chip on" data-diff="0">全部</button>
      <button class="chip" data-diff="1">★</button>
      <button class="chip" data-diff="2">★★</button>
      <button class="chip" data-diff="3">★★★</button>
      <button class="chip" id="onlyTodo">未掌握</button>
    </div>
    <div class="progress-wrap">
      <div class="progress-bar"><i id="pbar"></i></div>
      <span id="ptext">0 / {total}</span>
    </div>
    <button class="icon-btn" id="expandAll" title="展开全部" aria-label="展开全部">
      <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M6 8l4-4 4 4M6 12l4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
    </button>
    <button class="icon-btn" id="randomBtn" title="随机抽一题" aria-label="随机抽一题">
      <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M3 5h3l7 10h4M3 15h3l7-10h4" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M15 3l2 2-2 2M15 13l2 2-2 2" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
    </button>
    <button class="icon-btn" id="themeBtn" title="切换主题" aria-label="切换主题">
      <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" stroke-width="1.7"/><path d="M10 3a7 7 0 010 14z" fill="currentColor"/></svg>
    </button>
  </div>
</header>
"""

HERO = """
<div class="hero">
  <h1>Text2SQL Agent 面试题库</h1>
  <p>面向 Java 后端 / AI 应用开发岗，按「由浅入深、可追问」组织。答案默认收起 —— 先自己讲一遍，再点开对照。</p>
  <p>只围绕这一个项目：主链路、评测、检索、安全、缓存，以及它真实用到的 Java / Spring / PostgreSQL。所有数字取自实测：三次独立运行 84 / 85 / 86，280 项测试，53 条对抗，¥0.221 / 100 题。</p>
  <div class="stats">
    <div class="stat"><b>{total}</b><span>道题</span></div>
    <div class="stat"><b>{parts}</b><span>个章节</span></div>
    <div class="stat"><b>84–86</b><span>执行准确率</span></div>
    <div class="stat"><b>12</b><span>条稳定失败</span></div>
    <div class="stat"><b>¥0.0022</b><span>单题成本</span></div>
  </div>
</div>
"""

JS = r"""
(function(){
  var TOTAL = __TOTAL__;
  var LS_KEY = 't2s-interview-mastered-v1';
  var THEME_KEY = 't2s-interview-theme';

  var qs = Array.prototype.slice.call(document.querySelectorAll('.q'));
  var navItems = Array.prototype.slice.call(document.querySelectorAll('.nav-item'));
  var searchEl = document.getElementById('search');
  var chips = Array.prototype.slice.call(document.querySelectorAll('#chips .chip'));
  var onlyTodoEl = document.getElementById('onlyTodo');
  var pbar = document.getElementById('pbar');
  var ptext = document.getElementById('ptext');

  /* ---------- 掌握标记 ---------- */
  var mastered;
  try { mastered = new Set(JSON.parse(localStorage.getItem(LS_KEY) || '[]')); }
  catch(e){ mastered = new Set(); }

  function save(){ try{ localStorage.setItem(LS_KEY, JSON.stringify(Array.from(mastered))); }catch(e){} }

  function paintProgress(){
    var n = mastered.size;
    pbar.style.width = (n / TOTAL * 100).toFixed(1) + '%';
    ptext.textContent = n + ' / ' + TOTAL;
    navItems.forEach(function(a){
      var sec = document.getElementById(a.dataset.target);
      if(!sec) return;
      var all = sec.querySelectorAll('.q');
      var done = sec.querySelectorAll('.q.done');
      a.classList.toggle('done', all.length > 0 && all.length === done.length);
    });
  }

  qs.forEach(function(q){
    var mark = q.querySelector('.q-mark');
    if(mastered.has(q.id)) { q.classList.add('done'); mark.setAttribute('aria-checked','true'); }
    mark.addEventListener('click', function(ev){
      ev.stopPropagation();
      var on = q.classList.toggle('done');
      if(on) mastered.add(q.id); else mastered.delete(q.id);
      mark.setAttribute('aria-checked', on ? 'true' : 'false');
      save(); paintProgress();
    });
  });

  /* ---------- 展开 / 收起 ---------- */
  qs.forEach(function(q){
    var head = q.querySelector('.q-head');
    head.addEventListener('click', function(){
      var open = q.classList.toggle('open');
      head.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  });

  var allOpen = false;
  var EXPAND_ICON = '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M6 8l4-4 4 4M6 12l4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  var COLLAPSE_ICON = '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M6 4l4 4 4-4M6 16l4-4 4 4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  document.getElementById('expandAll').addEventListener('click', function(){
    allOpen = !allOpen;
    qs.forEach(function(q){
      if(q.hidden) return;
      q.classList.toggle('open', allOpen);
      q.querySelector('.q-head').setAttribute('aria-expanded', allOpen ? 'true' : 'false');
    });
    this.innerHTML = allOpen ? COLLAPSE_ICON : EXPAND_ICON;
    this.title = allOpen ? '收起全部' : '展开全部';
  });

  /* ---------- 筛选 ---------- */
  var diffFilter = 0, onlyTodo = false, kw = '';

  function apply(){
    var shown = 0;
    qs.forEach(function(q){
      var ok = true;
      if(diffFilter && +q.dataset.diff !== diffFilter) ok = false;
      if(ok && onlyTodo && q.classList.contains('done')) ok = false;
      if(ok && kw && q.textContent.toLowerCase().indexOf(kw) === -1) ok = false;
      q.hidden = !ok;
      if(ok) shown++;
    });
    document.querySelectorAll('.part').forEach(function(sec){
      var qlist = sec.querySelectorAll('.q');
      if(!qlist.length){
        // 没有题目的章节（附录 A 表格、附录 B 数字表）：只在关键词筛选时按文本命中决定
        sec.hidden = !!kw && sec.textContent.toLowerCase().indexOf(kw) === -1;
        return;
      }
      var vis = Array.prototype.some.call(qlist, function(q){ return !q.hidden; });
      sec.hidden = !vis;
    });
    var empty = document.getElementById('emptyState');
    if(!empty){
      empty = document.createElement('div');
      empty.id = 'emptyState'; empty.className = 'empty';
      empty.textContent = '没有匹配的题目，换个关键词试试。';
      document.querySelector('.main').appendChild(empty);
    }
    empty.classList.toggle('on', shown === 0);

    var hits = document.getElementById('hits');
    if(kw || diffFilter || onlyTodo){
      hits.textContent = shown + ' 题';
      hits.hidden = false;
    } else {
      hits.hidden = true;
    }
    return shown;
  }

  chips.forEach(function(c){
    c.addEventListener('click', function(){
      if(c === onlyTodoEl){
        onlyTodo = !onlyTodo;
        c.classList.toggle('on', onlyTodo);
      } else {
        diffFilter = +c.dataset.diff;
        chips.forEach(function(x){ if(x !== onlyTodoEl) x.classList.remove('on'); });
        c.classList.add('on');
      }
      apply();
    });
  });

  var timer;
  searchEl.addEventListener('input', function(){
    clearTimeout(timer);
    var v = searchEl.value.trim().toLowerCase();
    timer = setTimeout(function(){ kw = v; apply(); }, 110);
  });

  /* ---------- 主题 ---------- */
  var savedTheme = null;
  try{ savedTheme = localStorage.getItem(THEME_KEY); }catch(e){}
  if(!savedTheme){
    savedTheme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  document.documentElement.setAttribute('data-theme', savedTheme);
  document.getElementById('themeBtn').addEventListener('click', function(){
    var next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try{ localStorage.setItem(THEME_KEY, next); }catch(e){}
  });

  /* ---------- 随机抽题 ---------- */
  document.getElementById('randomBtn').addEventListener('click', function(){
    var pool = qs.filter(function(q){ return !q.hidden; });
    if(!pool.length) return;
    var q = pool[Math.floor(Math.random() * pool.length)];
    qs.forEach(function(x){ x.classList.remove('open'); });
    q.classList.add('open');
    q.querySelector('.q-head').setAttribute('aria-expanded','true');
    q.scrollIntoView({behavior:'smooth', block:'center'});
  });

  /* ---------- 目录高亮 ---------- */
  var obs = new IntersectionObserver(function(entries){
    entries.forEach(function(en){
      if(!en.isIntersecting) return;
      navItems.forEach(function(a){ a.classList.toggle('active', a.dataset.target === en.target.id); });
    });
  }, {rootMargin:'-70px 0px -70% 0px', threshold:0});
  document.querySelectorAll('.part').forEach(function(s){ obs.observe(s); });

  /* ---------- 快捷键 ---------- */
  document.addEventListener('keydown', function(ev){
    if(ev.key === '/' && document.activeElement !== searchEl){
      ev.preventDefault(); searchEl.focus();
    } else if(ev.key === 'Escape' && document.activeElement === searchEl){
      searchEl.value = ''; kw = ''; apply(); searchEl.blur();
    }
  });

  /* ---------- 回到顶部 ---------- */
  var toTop = document.getElementById('toTop');
  window.addEventListener('scroll', function(){
    toTop.classList.toggle('on', window.scrollY > 600);
  }, {passive:true});
  toTop.addEventListener('click', function(){ window.scrollTo({top:0, behavior:'smooth'}); });

  /* ---------- 首屏：打开第一部分的第一题作为示例 ---------- */
  var first = qs[0];
  if(first){
    first.classList.add('open');
    first.querySelector('.q-head').setAttribute('aria-expanded','true');
  }

  paintProgress();
  apply();
})();
"""


if __name__ == "__main__":
    total, size = build()
    print("OK  %s  |  %d 题  |  %.1f KB" % (OUT.relative_to(ROOT), total, size / 1024))
