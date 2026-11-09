# -*- coding: utf-8 -*-
"""
生成《Text2SQL Agent 核心面试实操与技术问答宝典》（小林coding 深度图解风格）。
产出: docs/text2sql-interview-guide.html
"""

from pathlib import Path
import html

OUT_FILE = Path(__file__).resolve().parent.parent / "docs" / "text2sql-interview-guide.html"

def card(qid, category, stars, tags, title, body_html):
    star_str = "★" * stars + "☆" * (3 - stars)
    tag_html = "".join(f'<span class="tag-pill">{t}</span>' for t in tags)
    return f"""
    <div class="q-card" id="{qid}" data-category="{category}">
      <div class="q-header" onclick="toggleCard(this)">
        <div class="q-icon">{qid[1:]}</div>
        <div class="q-title-wrap">
          <div class="q-title">{title}</div>
          <div class="q-meta">
            <span class="difficulty-stars">{star_str}</span>
            {tag_html}
          </div>
        </div>
        <div class="q-toggle">▼</div>
      </div>
      <div class="q-body">
        {body_html}
      </div>
    </div>
    """

def callout(kind, title, text):
    icons = {
        "direct": "💡",
        "principle": "📌",
        "interview": "⚔️",
        "warning": "⚠️",
        "success": "✅"
    }
    icon = icons.get(kind, "💡")
    return f"""
    <div class="callout {kind}">
      <div class="callout-title">{icon} {title}</div>
      <div>{text}</div>
    </div>
    """

def code_block(code_str):
    escaped = html.escape(code_str.strip())
    return f"""<pre class="code-block"><code>{escaped}</code></pre>"""

def table(headers, rows):
    th_html = "".join(f"<th>{h}</th>" for h in headers)
    tr_html = "".join("<tr>" + "".join(f"<td>{cell}</td>" for cell in r) + "</tr>" for r in rows)
    return f"""
    <table class="content-table">
      <thead><tr>{th_html}</tr></thead>
      <tbody>{tr_html}</tbody>
    </table>
    """

HTML_SHELL = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Text2SQL Agent 核心技术面试宝典（大厂架构与实战深度图解版）</title>
<style>
:root {
  --primary: #0284c7;
  --primary-dark: #0369a1;
  --primary-light: #e0f2fe;
  --accent: #0f766e;
  --accent-light: #ccfbf1;
  --bg-main: #f8fafc;
  --bg-card: #ffffff;
  --text-main: #0f172a;
  --text-muted: #475569;
  --text-sub: #64748b;
  --border: #e2e8f0;
  --border-focus: #38bdf8;
  --success: #10b981;
  --success-bg: #ecfdf5;
  --warning: #f59e0b;
  --warning-bg: #fffbeb;
  --danger: #ef4444;
  --danger-bg: #fef2f2;
  --purple: #8b5cf6;
  --purple-bg: #f5f3ff;
  --code-bg: #0f172a;
  --code-text: #e2e8f0;
  --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  --font-mono: "Fira Code", "Cascadia Code", Consolas, Monaco, "Courier New", monospace;
  --shadow-sm: 0 1px 2px 0 rgb(0 0 0 / 0.05);
  --shadow-md: 0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1);
  --shadow-lg: 0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.1);
}

* { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: var(--font-sans);
  background-color: var(--bg-main);
  color: var(--text-main);
  line-height: 1.7;
  -webkit-font-smoothing: antialiased;
}

/* Header & Banner */
header.hero {
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
  color: #ffffff;
  padding: 48px 24px 40px;
  border-bottom: 3px solid var(--primary);
  text-align: center;
}
header.hero h1 {
  font-size: 2.2rem;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin-bottom: 12px;
}
header.hero h1 span {
  color: #38bdf8;
}
header.hero p {
  font-size: 1.05rem;
  color: #94a3b8;
  max-width: 860px;
  margin: 0 auto 20px;
}
.badge-group {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 14px;
}
.badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: 9999px;
  font-size: 0.82rem;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.1);
  color: #f1f5f9;
  border: 1px solid rgba(255, 255, 255, 0.15);
}
.badge.highlight {
  background: rgba(56, 189, 248, 0.2);
  color: #38bdf8;
  border-color: rgba(56, 189, 248, 0.4);
}

/* Container & Layout */
.container {
  max-width: 1200px;
  margin: 32px auto;
  padding: 0 20px;
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 32px;
  align-items: start;
}

/* Sidebar Nav */
aside.sidebar {
  position: sticky;
  top: 24px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
  box-shadow: var(--shadow-sm);
  max-height: calc(100vh - 48px);
  overflow-y: auto;
}
aside.sidebar h3 {
  font-size: 0.95rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-sub);
  margin-bottom: 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border);
}
.search-box {
  margin-bottom: 16px;
}
.search-box input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 0.88rem;
  outline: none;
  transition: all 0.2s;
}
.search-box input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(2, 132, 199, 0.15);
}
.nav-group {
  margin-bottom: 16px;
}
.nav-group-title {
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-muted);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.nav-list {
  list-style: none;
}
.nav-list li {
  margin-bottom: 6px;
}
.nav-list a {
  display: block;
  font-size: 0.84rem;
  color: var(--text-muted);
  text-decoration: none;
  padding: 4px 8px;
  border-radius: 6px;
  transition: all 0.15s;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.nav-list a:hover {
  background: var(--primary-light);
  color: var(--primary-dark);
}

/* Main Content Area */
main.content {
  min-width: 0;
}

/* Category Filter Tags */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 24px;
  background: var(--bg-card);
  padding: 12px 16px;
  border-radius: 10px;
  border: 1px solid var(--border);
}
.filter-btn {
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid var(--border);
  background: var(--bg-main);
  color: var(--text-muted);
  transition: all 0.2s;
}
.filter-btn:hover, .filter-btn.active {
  background: var(--primary);
  color: #ffffff;
  border-color: var(--primary);
}
.tool-btns {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.action-btn {
  padding: 6px 12px;
  font-size: 0.8rem;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: #ffffff;
  color: var(--text-muted);
  cursor: pointer;
}
.action-btn:hover {
  background: var(--bg-main);
  color: var(--text-main);
}

/* Section Title */
.section-header {
  margin: 36px 0 20px;
  padding-bottom: 8px;
  border-bottom: 2px solid var(--primary);
  display: flex;
  align-items: center;
  gap: 12px;
}
.section-header h2 {
  font-size: 1.45rem;
  font-weight: 800;
  color: var(--text-main);
}
.section-tag {
  background: var(--primary-light);
  color: var(--primary-dark);
  font-size: 0.75rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 4px;
}

/* Question Card */
.q-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  transition: box-shadow 0.2s, border-color 0.2s;
}
.q-card:hover {
  box-shadow: var(--shadow-md);
  border-color: #cbd5e1;
}
.q-header {
  padding: 18px 24px;
  background: #ffffff;
  cursor: pointer;
  display: flex;
  align-items: flex-start;
  gap: 14px;
  border-bottom: 1px solid transparent;
  user-select: none;
}
.q-header.open {
  border-bottom-color: var(--border);
  background: #fafbfc;
}
.q-icon {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--primary-light);
  color: var(--primary-dark);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: 0.85rem;
  margin-top: 2px;
}
.q-title-wrap {
  flex-grow: 1;
}
.q-title {
  font-size: 1.12rem;
  font-weight: 700;
  color: var(--text-main);
  line-height: 1.45;
}
.q-meta {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  align-items: center;
}
.tag-pill {
  font-size: 0.72rem;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg-main);
  color: var(--text-sub);
  border: 1px solid var(--border);
}
.difficulty-stars {
  color: #f59e0b;
  font-size: 0.75rem;
  font-weight: 700;
}
.q-toggle {
  color: var(--text-sub);
  font-size: 1.2rem;
  transition: transform 0.2s;
}
.q-header.open .q-toggle {
  transform: rotate(180deg);
}

.q-body {
  padding: 24px;
  display: none;
}
.q-body.open {
  display: block;
}

/* Callout Boxes (XiaoLin Coding Style) */
.callout {
  border-radius: 8px;
  padding: 14px 18px;
  margin: 16px 0;
  font-size: 0.94rem;
  line-height: 1.65;
  border-left: 4px solid;
}
.callout.direct {
  background: var(--primary-light);
  border-color: var(--primary);
  color: #0c4a6e;
}
.callout.direct strong {
  color: #0369a1;
}
.callout.principle {
  background: var(--purple-bg);
  border-color: var(--purple);
  color: #4c1d95;
}
.callout.principle strong {
  color: #6d28d9;
}
.callout.interview {
  background: var(--danger-bg);
  border-color: var(--danger);
  color: #7f1d1d;
}
.callout.interview strong {
  color: #b91c1c;
}
.callout.warning {
  background: var(--warning-bg);
  border-color: var(--warning);
  color: #78350f;
}
.callout.warning strong {
  color: #b45309;
}
.callout.success {
  background: var(--success-bg);
  border-color: var(--success);
  color: #064e3b;
}

.callout-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 800;
  font-size: 0.98rem;
  margin-bottom: 6px;
}

/* Typography in Q-Body */
.q-body h4 {
  font-size: 1.02rem;
  font-weight: 800;
  color: var(--text-main);
  margin: 20px 0 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.q-body p {
  margin-bottom: 12px;
  font-size: 0.95rem;
  color: #334155;
}
.q-body ul, .q-body ol {
  margin: 10px 0 16px 24px;
  font-size: 0.94rem;
  color: #334155;
}
.q-body li {
  margin-bottom: 6px;
}

/* Tables in Q-Body */
.content-table {
  width: 100%;
  border-collapse: collapse;
  margin: 16px 0 20px;
  font-size: 0.88rem;
}
.content-table th, .content-table td {
  padding: 10px 14px;
  border: 1px solid var(--border);
  text-align: left;
}
.content-table th {
  background: #f1f5f9;
  font-weight: 700;
  color: var(--text-main);
}
.content-table tr:nth-child(even) {
  background: #f8fafc;
}

/* Code Snippets */
pre.code-block {
  background: var(--code-bg);
  color: var(--code-text);
  padding: 16px 18px;
  border-radius: 8px;
  font-family: var(--font-mono);
  font-size: 0.86rem;
  overflow-x: auto;
  margin: 14px 0 18px;
  line-height: 1.55;
  border: 1px solid #1e293b;
}
code.inline-code {
  font-family: var(--font-mono);
  background: #e2e8f0;
  color: #0f172a;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.86em;
}

/* Visual Diagrams (SVG/Flex based) */
.diagram-wrap {
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  padding: 16px;
  margin: 16px 0;
  text-align: center;
}
.flow-diagram {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.flow-node {
  background: #ffffff;
  border: 1.5px solid var(--primary);
  border-radius: 6px;
  padding: 8px 14px;
  font-size: 0.84rem;
  font-weight: 700;
  color: var(--text-main);
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}
.flow-node.danger {
  border-color: var(--danger);
  color: var(--danger);
  background: var(--danger-bg);
}
.flow-node.success {
  border-color: var(--success);
  color: #065f46;
  background: var(--success-bg);
}
.flow-arrow {
  color: var(--primary);
  font-weight: 900;
  font-size: 1rem;
}

/* Footer */
footer {
  text-align: center;
  padding: 36px 20px;
  color: var(--text-sub);
  font-size: 0.86rem;
  border-top: 1px solid var(--border);
  margin-top: 48px;
}

/* Responsive */
@media (max-width: 900px) {
  .container {
    grid-template-columns: 1fr;
  }
  aside.sidebar {
    display: none;
  }
}
</style>
</head>
<body>

<header class="hero">
  <h1>Text2SQL Agent <span>核心面试实操与技术问答宝典</span></h1>
  <p>以大厂资深架构师视角拆解：从图论拓扑规划、AST 安全审计、EXPLAIN 代价护栏，到指标语义层与基准消融归因的硬核实操</p>
  <div class="badge-group">
    <span class="badge highlight">Java 21 / Spring Boot 3.5</span>
    <span class="badge highlight">PostgreSQL 16 架构</span>
    <span class="badge">斯坦纳树近似算法</span>
    <span class="badge">JSqlParser AST 白名单</span>
    <span class="badge">同模型 62% → 96.00% 归因</span>
    <span class="badge">53 条对抗渗透拦截</span>
  </div>
</header>

<div class="container">
  <!-- Sidebar -->
  <aside class="sidebar">
    <h3>面试导航</h3>
    <div class="search-box">
      <input type="text" id="searchInput" placeholder="搜索面试考点或关键词..." oninput="handleSearch()">
    </div>
    
    <div class="nav-group">
      <div class="nav-group-title">一、宏观架构与设计理念</div>
      <ul class="nav-list">
        <li><a href="#q01">Q01 项目介绍与业务边界</a></li>
        <li><a href="#q02">Q02 端到端数据流与耗时拆解</a></li>
        <li><a href="#q03">Q03 为什么选 Pipeline 舍弃 ReAct</a></li>
      </ul>
    </div>

    <div class="nav-group">
      <div class="nav-group-title">二、Schema 检索与图算法</div>
      <ul class="nav-list">
        <li><a href="#q04">Q04 为什么必须做 Schema 剪枝</a></li>
        <li><a href="#q05">Q05 领域词典 vs 向量检索对比</a></li>
        <li><a href="#q06">Q06 多表关联的本质难题</a></li>
        <li><a href="#q07">Q07 Dijkstra 局限与斯坦纳树算法</a></li>
      </ul>
    </div>

    <div class="nav-group">
      <div class="nav-group-title">三、语义层与 Prompt 工程</div>
      <ul class="nav-list">
        <li><a href="#q08">Q08 指标歧义与 MDL 语义层建模</a></li>
        <li><a href="#q09">Q09 提示词分层与近因效应设计</a></li>
        <li><a href="#q10">Q10 温度设 0 与生成确定性边界</a></li>
      </ul>
    </div>

    <div class="nav-group">
      <div class="nav-group-title">四、安全防御与执行护栏</div>
      <ul class="nav-list">
        <li><a href="#q11">Q11 纵深防御体系整体设计</a></li>
        <li><a href="#q12">Q12 正则短板与 AST 语法树遍历</a></li>
        <li><a href="#q13">Q13 EXPLAIN JSON 代价探测原理</a></li>
        <li><a href="#q14">Q14 为什么必须有只读角色兜底</a></li>
      </ul>
    </div>

    <div class="nav-group">
      <div class="nav-group-title">五、性能优化与工程落地</div>
      <ul class="nav-list">
        <li><a href="#q15">Q15 语法缓存 vs 结果集缓存权衡</a></li>
        <li><a href="#q16">Q16 自定义密钥与 LRU 客户端池</a></li>
        <li><a href="#q17">Q17 P95 延迟突破与生产优化方案</a></li>
      </ul>
    </div>

    <div class="nav-group">
      <div class="nav-group-title">六、评测基准与数据治理</div>
      <ul class="nav-list">
        <li><a href="#q18">Q18 执行比对 vs 字符串/LLM裁判</a></li>
        <li><a href="#q19">Q19 62% 到 96% 的消融归因台账</a></li>
        <li><a href="#q20">Q20 面对“修改测试集作弊”的自证</a></li>
        <li><a href="#q21">Q21 为什么主动关闭自纠错重试</a></li>
      </ul>
    </div>
  </aside>

  <!-- Content -->
  <main class="content">
    <div class="filter-bar">
      <button class="filter-btn active" onclick="filterCategory('all', this)">全部 (21)</button>
      <button class="filter-btn" onclick="filterCategory('cat1', this)">架构宏观</button>
      <button class="filter-btn" onclick="filterCategory('cat2', this)">图算法/检索</button>
      <button class="filter-btn" onclick="filterCategory('cat3', this)">语义层/Prompt</button>
      <button class="filter-btn" onclick="filterCategory('cat4', this)">安全/AST护栏</button>
      <button class="filter-btn" onclick="filterCategory('cat5', this)">高并发/工程化</button>
      <button class="filter-btn" onclick="filterCategory('cat6', this)">评测/消融归因</button>
      <div class="tool-btns">
        <button class="action-btn" onclick="expandAll(true)">全部展开</button>
        <button class="action-btn" onclick="expandAll(false)">全部折叠</button>
      </div>
    </div>

    <!-- QUESTIONS CONTENT INJECTED HERE -->
    __QUESTIONS_PLACEHOLDER__

  </main>
</div>

<footer>
  <p>Text2SQL Agent 技术与面试答辩全集 · 基于真实电商数仓评测与生产级 Java 后端防御工程</p>
</footer>

<script>
function toggleCard(header) {
  header.classList.toggle('open');
  const body = header.nextElementSibling;
  body.classList.toggle('open');
}

function expandAll(open) {
  document.querySelectorAll('.q-header').forEach(h => {
    if (open) {
      h.classList.add('open');
      h.nextElementSibling.classList.add('open');
    } else {
      h.classList.remove('open');
      h.nextElementSibling.classList.remove('open');
    }
  });
}

function filterCategory(cat, btn) {
  document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  document.querySelectorAll('.q-card').forEach(card => {
    if (cat === 'all' || card.dataset.category === cat) {
      card.style.display = 'block';
    } else {
      card.style.display = 'none';
    }
  });
  document.querySelectorAll('.section-group').forEach(sec => {
    if (cat === 'all') {
      sec.style.display = 'block';
    } else if (sec.id === 'sec-' + cat) {
      sec.style.display = 'block';
    } else {
      sec.style.display = 'none';
    }
  });
}

function handleSearch() {
  const query = document.getElementById('searchInput').value.trim().toLowerCase();
  document.querySelectorAll('.q-card').forEach(card => {
    const text = card.innerText.toLowerCase();
    if (!query || text.includes(query)) {
      card.style.display = 'block';
    } else {
      card.style.display = 'none';
    }
  });
}
</script>
</body>
</html>
"""

def main():
    import guide_parts_1_2
    import guide_parts_3_4
    import guide_parts_5_6

    p1 = guide_parts_1_2.build_part1()
    p2 = guide_parts_1_2.build_part2()
    p3 = guide_parts_3_4.build_part3()
    p4 = guide_parts_3_4.build_part4()
    p5 = guide_parts_5_6.build_part5()
    p6 = guide_parts_5_6.build_part6()

    all_content = "\n".join([p1, p2, p3, p4, p5, p6])
    full_html = HTML_SHELL.replace("__QUESTIONS_PLACEHOLDER__", all_content)

    OUT_FILE.write_text(full_html, encoding="utf-8")
    print(f"OK: 成功构建 HTML 题库宝典 -> {OUT_FILE} (大小: {OUT_FILE.stat().st_size / 1024:.1f} KB)")

if __name__ == "__main__":
    main()
