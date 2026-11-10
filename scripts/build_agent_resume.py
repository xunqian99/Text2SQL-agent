# -*- coding: utf-8 -*-
"""生成专门针对 AI 应用开发 / Agent 研发岗位的简历。
基于原版简历-江松涛.docx，保持所有排版、字体、字号、边距不变，
只重写求职意向、Text2SQL Agent 经历与专业技能，
用词客观严谨、彻底消除浮夸用语（如自研、顶尖、神级等）。
"""
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
        ("求职意向：Java 后端开发工程师",
         "求职意向：**AI 应用开发工程师 / Agent 研发工程师（Java 方向）**"),

        # 项目介绍
        ("项目介绍：面向企业电商数仓复杂拓扑与大模型幻觉难题",
         "**项目介绍：**面向企业电商数仓高精度问数场景，主导开发基于 Java 21 的 "
         "**Text2SQL 数据智能体（Data Agent）**系统，实现自然语言到生产级只读 SQL 的自动化推理与安全执行；"
         "项目开源：**github.com/xunqian99/Text2SQL-agent**。"),

        # 1. 上下文工程与拓扑剪枝
        ("• 多表关联与拓扑规划：针对 37 张表",
         "• **上下文工程与拓扑剪枝：**针对 37 张表全量 DDL 严重挤占 LLM 上下文窗口（7401 字符）难题，"
         "设计领域词典检索与关系扩散算法，将上下文**压缩 82.7%**（降至 1278 字符），表全召回率达 **99.00%**；"
         "基于外键图实现**斯坦纳树（Steiner Tree）近似算法**进行路径规划，消除局部贪心伪支路，"
         "3 表关联准确率达 **100%**，4 表及以上复杂查询准确率达到 **90.91%**。"),

        # 2. 智能体工具调用 (替换原执行计划)
        ("• 执行计划代价防御：为防止大表扫描",
         "• **智能体工具调用（Tool Calling & ReAct）：**遵循 OpenAI Function Calling 规范设计智能体交互中枢，"
         "为模型提供表结构探查（INSPECT_TABLE）、列枚举采样（INSPECT_COLUMN）、轻量验证查询（SAMPLE_QUERY）等 "
         "**5 项数仓侦察工具**；在多表复杂推理时，模型可自主按需调用工具探测真实数据分布与关联边界，辅助决策最终 SQL。"),

        # 3. 语法护栏与代价防御 (合并原语法审计 + EXPLAIN)
        ("• 语法审计与安全隔离：基于 JSqlParser AST",
         "• **语法护栏与代价防御（Guardrails）：**为防止模型越狱、Prompt 注入与恶性慢查询，"
         "基于 **JSqlParser AST 语法树**构建编译级只读白名单，递归拦截可写 CTE 逃逸与高危系统函数；"
         "解析 PostgreSQL **EXPLAIN JSON** 执行计划树，在物理执行前精准识别无约束全表笛卡尔积并实施硬拦截；"
         "结合只读账号与 10 秒超时实现三层纵深防御，**53 条对抗渗透测试用例 100% 拦截全绿**。"),

        # 4. 评测体系与消融分析
        ("• 评测体系与数据治理：搭建覆盖 6 层难度",
         "• **评测体系与消融分析（Eval-Driven）：**搭建覆盖 6 层难度的 200 题基准评测集，"
         "端到端执行准确率由初始基线的 **62%** 提升至 **96.00%**，SQL 有效执行率 **100%**；"
         "坚持数据驱动的工程决策，通过严格的消融实验实测**定位启发式重试增益为 0 并存在误改风险，主动关闭非必要重试**"
         "以消除无效 Token 损耗，使单次查询平均推理成本降至约 **0.002 元**。"),

        # 5. 语义层建模与多轮对话 (替换原性能优化)
        ("• 性能优化与工程落地：设计基于 SQL 语法归一化",
         "• **语义层建模与多轮对话管理：**对齐工业级建模定义语言（MDL）规范，通过指标字典注入标准计算公式与口径约束；"
         "实现基于大模型的**多轮会话指代消解与滑动窗口摘要压缩**，长会话 **Token 消耗降低 67.2%**；"
         "引入**业务口径歧义主动反问机制（Human-in-the-Loop）**，针对“销售额”等未定义运费范围的高危模糊词实施前置拦截与口径确认，避免静默业务错误。"),

        # 专业技能 - AI Agent
        ("• AI Agent 研发：深入掌握 Text2SQL 核心链路",
         "• **AI Agent 研发：**深入掌握大模型 Agent 核心链路（**ReAct 范式、Function Calling 工具编排、多轮指代消解与会话压缩**）；"
         "熟练掌握基于斯坦纳树的结构化上下文工程与 **JSqlParser AST 安全护栏**；具备扎实的 LLM 评测集设计、消融实验与指标量化归因能力；"),
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
                    if re.search(r"<w:b\s*/>", rp):
                        if not bold_rpr:
                            bold_rpr = rp
                    else:
                        if not norm_rpr:
                            norm_rpr = rp
                if not bold_rpr:
                    bold_rpr = norm_rpr
                if not norm_rpr:
                    norm_rpr = re.sub(r"<w:b\s*/>", "", bold_rpr)
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
    shutil.move(TMP, DST)
    print("成功生成专投 Agent 岗位的新简历：", DST)


if __name__ == "__main__":
    main()
