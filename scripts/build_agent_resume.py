# -*- coding: utf-8 -*-
"""生成专门针对 AI 应用开发 / Agent 研发岗位的简历。
基于原版简历-江松涛.docx，保持所有排版、字体、字号、边距不变，
只重写求职意向、Text2SQL Agent 经历与专业技能。

严格要求：
1. 严禁自嗨与浮夸词汇（如自研、顶尖、交互中枢、全绿等），用词扎实严谨。
2. 严格尊重用户的已有修改（如“实测定位启发式重试增益微薄，关闭重试以消除无效 Token 损耗。”，不额外添加重复链接等）。
3. 严格控制字数与行数，与原版段落保持 1:1，确保严格单页（1 页）排版。
"""
import os
import re
import shutil
import zipfile

SRC = "简历-江松涛.docx"
DST = "简历-江松涛-AI应用开发.docx"
TMP = DST + ".tmp"


def clean_tag(tag):
    tag = re.sub(r'\sw14:paraId="[0-9A-Fa-f]+"', "", tag)
    tag = re.sub(r'\sw14:textId="[0-9A-Fa-f]+"', "", tag)
    return tag


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def extract_text(block):
    t = re.findall(r"<w:t(?:\s[^>]*)?>(.*?)</w:t>", block, re.S)
    return "".join(t)


def build_runs(markup, bold_rpr, norm_rpr):
    out = []
    for i, seg in enumerate(re.split(r"\*\*", markup)):
        if not seg:
            continue
        rpr = bold_rpr if i % 2 == 1 else norm_rpr
        out.append('<w:r>%s<w:t xml:space="preserve">%s</w:t></w:r>' % (rpr, esc(seg)))
    return "".join(out)


def para_xml(open_tag, ppr, runs):
    return "%s%s%s</w:p>" % (open_tag, ppr, runs)


def main():
    z = zipfile.ZipFile(SRC)
    xml = z.read("word/document.xml").decode("utf-8")
    names = z.namelist()
    payload = {n: z.read(n) for n in names if n != "word/document.xml"}
    z.close()

    EDITS = [
        # 求职意向
        (
            "求职意向：Java 后端开发工程师",
            "求职意向：**AI 应用开发工程师 / Agent 研发工程师（Java 方向）**"
        ),

        # 项目介绍 (严格 85 字，与原版完全一致，不塞重复链接)
        (
            "项目介绍：面向企业电商数仓复杂拓扑与大模型幻觉难题",
            "**项目介绍：**面向企业电商数仓，主导开发基于 Java 21 的 **Text2SQL 数据智能体（Data Agent）**，实现自然语言到生产级只读 SQL 的自动化推理与安全执行。"
        ),

        # 1. 上下文工程与拓扑剪枝 (135 字，与原版 135 字完全一致)
        (
            "• 多表关联与拓扑规划：针对 37 张表",
            "• **上下文工程与拓扑剪枝：**针对 37 表全量 DDL 挤占上下文，设计领域检索与外键扩散，将 Schema 上下文**压缩 82.7%**，表召回率达 **99.00%**；基于**斯坦纳树近似算法**规划外键路径，消除局部贪心伪支路，3 表关联准确率 **100%**，多表复杂查询达 **90.91%**。"
        ),

        # 2. 智能体工具调用 (113 字，与原版 113 字一字不差，客观严谨)
        (
            "• 执行计划代价防御：为防止大表扫描",
            "• **智能体工具调用（Tool Calling）：**遵循 Function Calling 规范设计调用链路，为模型提供表结构探查、列枚举采样与轻量试查工具；在多表复杂推理时，引导模型按需探查数据分布与关联边界，辅助决策最终 SQL。"
        ),

        # 3. 评测体系与消融分析 (165 字，与原版 165 字一字不差，严格保留用户修改)
        (
            "• 评测体系与数据治理：搭建覆盖 6 层难度",
            "• **评测体系与消融分析（Eval-Driven）：**搭建覆盖 6 层难度的 200 题基准评测集，同模型对照下端到端执行准确率由 **62%** 提升至 **96.00%**，SQL 有效执行率 **100%**；通过执行日志归因完成数据治理并消除口径冲突；**实测定位启发式重试增益微薄，关闭重试以消除无效 Token 损耗**，单次推理成本约 **0.002 元**。"
        ),

        # 4. 语法护栏与代价防御 (140 字，比原版 145 字少 5 字，严谨无黑话)
        (
            "• 语法审计与安全隔离：基于 JSqlParser AST",
            "• **语法护栏与代价防御（Guardrails）：**基于 **JSqlParser AST** 构建编译级只读白名单，拦截可写 CTE 与高危函数；解析 PostgreSQL **EXPLAIN JSON** 执行计划，物理执行前拦截无约束全表笛卡尔积；结合只读账号与超时三层防御，**53 条对抗渗透用例均通过验证**。"
        ),

        # 5. 语义层建模与会话管理 (121 字，与原版 121 字一字不差，结合 Human-in-the-Loop)
        (
            "• 性能优化与工程落地：设计基于 SQL 语法归一化",
            "• **语义层建模与会话管理：**对齐指标字典注入标准口径约束；实现多轮会话指代消解与滑动窗口摘要压缩，长会话 **Token 消耗降低 67.2%**；引入主动反问机制（**Human-in-the-Loop**），对“销售额”等模糊词前置确认口径，避免静默业务错误。"
        ),

        # 专业技能 - AI Agent (112 字，与原版 112 字一字不差)
        (
            "• AI Agent 研发：深入掌握 Text2SQL 核心链路",
            "• **AI Agent 研发：**掌握智能体核心链路（**ReAct 范式、Function Calling 工具编排、多轮指代消解与会话压缩**）；熟练掌握斯坦纳树上下文工程与 **AST 安全护栏**；具备基准评测集设计与消融归因能力；"
        ),
    ]

    done = set()

    def repl(m):
        open_tag, inner, close = m.group(1), m.group(2), m.group(3)
        text = extract_text(m.group(0))
        for i, (prefix, new_md) in enumerate(EDITS):
            if i in done:
                continue
            if text.startswith(prefix):
                ppr_m = re.match(r"(<w:pPr>.*?</w:pPr>)", inner, re.S)
                ppr = ppr_m.group(1) if ppr_m else ""
                body = inner[len(ppr):]
                rprs = re.findall(r"<w:rPr>.*?</w:rPr>", body, re.S)
                bold_rpr = ""
                norm_rpr = ""
                for rp in rprs:
                    if "<w:b/>" in rp or ("<w:b " in rp and 'w:val="0"' not in rp):
                        if not bold_rpr:
                            bold_rpr = rp
                    else:
                        if not norm_rpr:
                            norm_rpr = rp
                if not bold_rpr:
                    bold_rpr = norm_rpr
                if not norm_rpr:
                    norm_rpr = re.sub(r"<w:b[^>]*/>", '<w:b w:val="0"/>', bold_rpr)
                paras = []
                for k, chunk in enumerate(new_md.split("\n\n")):
                    runs = build_runs(chunk, bold_rpr, norm_rpr)
                    tag = open_tag if k == 0 else clean_tag(open_tag)
                    paras.append(para_xml(tag, ppr, runs))
                done.add(i)
                return "".join(paras)
        return m.group(0)

    new_xml = re.sub(r"(<w:p(?: [^>]*)?>)(.*?)(</w:p>)", repl, xml, flags=re.S)

    missing = [EDITS[i][0] for i in range(len(EDITS)) if i not in done]
    if missing:
        print("未命中替换项:", missing)
    else:
        print("全部条目替换成功！")

    with zipfile.ZipFile(TMP, "w", zipfile.ZIP_DEFLATED) as zo:
        for n in names:
            if n == "word/document.xml":
                zo.writestr(n, new_xml.encode("utf-8"))
            else:
                zo.writestr(n, payload[n])

    if os.path.exists(DST):
        os.remove(DST)
    shutil.move(TMP, DST)
    print("成功生成: %s" % DST)


if __name__ == "__main__":
    main()
