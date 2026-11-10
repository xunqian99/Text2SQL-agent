# -*- coding: utf-8 -*-
"""更新后端简历 简历-江松涛.docx。
保持所有排版、字体、字号、边距不变。
修改求职意向、Text2SQL 第 19 条（质量工程与数据驱动决策）、专业技能第 36 条。
"""
import os
import re
import shutil
import zipfile

SRC = "简历-江松涛.docx"
TMP = SRC + ".tmp"


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


def update_resume(skill_option="A"):
    z = zipfile.ZipFile(SRC)
    xml = z.read("word/document.xml").decode("utf-8")
    names = z.namelist()
    payload = {n: z.read(n) for n in names if n != "word/document.xml"}
    z.close()

    skill_text = (
        "• **AI Agent 研发：**掌握智能体架构（**ReAct 范式、Function Calling 工具编排**）；"
        "熟悉**上下文工程**与多轮会话压缩；掌握输入输出**安全护栏（Guardrails）**；具备 **LLM 基准评测集设计与消融分析**能力；"
        if skill_option == "A" else
        "• **AI 工程化落地：**掌握大模型后端集成架构（**Function Calling 工具编排、ReAct 范式**）；"
        "熟悉**动态上下文剪枝**与 Token 预算控制；掌握编译级**安全护栏（Guardrails）**与防注入机制；具备 **LLM 基准评测与消融归因**能力；"
    )

    EDITS = [
        # 1. 求职意向：纯正后端
        (
            "求职意向：Java 后端开发工程师",
            "求职意向：**Java 后端开发工程师**"
        ),
        # 2. 项目 1 第 19 条：质量工程与数据驱动决策 (160 字，保留用户的微薄关闭重试)
        (
            "• 评测体系与数据治理：",
            "• **质量工程与数据驱动决策：**搭建覆盖 6 层难度的 200 题基准回归测试集，在同模型对照下，"
            "端到端执行准确率由 **62%** 稳步提升至 **96.00%**，SQL 有效执行率 **100%**；"
            "通过全链路执行日志归因定位并消除口径冲突，构建高覆盖自动化防御网；"
            "**实测定位启发式重试增益微薄，关闭重试以消除无效 Token 损耗**。"
        ),
        # 3. 专业技能第 36 条
        (
            "• AI Agent 研发：",
            skill_text
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

    try:
        if os.path.exists(SRC):
            os.remove(SRC)
        shutil.move(TMP, SRC)
        print("成功更新: %s" % SRC)
    except PermissionError:
        alt = "简历-江松涛(最新).docx"
        if os.path.exists(alt):
            os.remove(alt)
        shutil.move(TMP, alt)
        print("提示: %s 正被 WPS/Word 占用，已优先写入到: %s" % (SRC, alt))


if __name__ == "__main__":
    import sys
    opt = sys.argv[1] if len(sys.argv) > 1 else "A"
    update_resume(opt)
