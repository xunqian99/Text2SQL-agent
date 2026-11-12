import sys
import os
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.enum.text import PP_ALIGN
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE

def hex_to_rgb(hex_str):
    hex_str = hex_str.lstrip('#')
    return RGBColor(*(int(hex_str[i:i+2], 16) for i in (0, 2, 4)))

# 配色系统
C_BG = hex_to_rgb('0B1120')        # 深邃夜空底色
C_CARD = hex_to_rgb('1E293B')      # 卡片底色 (Slate-800)
C_CARD_LIGHT = hex_to_rgb('162032')# 次级卡片
C_BORDER = hex_to_rgb('334155')    # 边框线 (Slate-700)
C_PRIMARY = hex_to_rgb('38BDF8')   # 核心高亮青蓝 (Sky-400)
C_SUCCESS = hex_to_rgb('34D399')   # 成功翠绿 (Emerald-400)
C_DANGER = hex_to_rgb('F87171')    # 危险告警红 (Red-400)
C_WARN = hex_to_rgb('FBBF24')      # 提示金黄 (Amber-400)
C_TEXT_MAIN = hex_to_rgb('F8FAFC') # 主文本 (Slate-50)
C_TEXT_MUTED = hex_to_rgb('94A3B8')# 辅助文本 (Slate-400)
C_TEXT_SUB = hex_to_rgb('CBD5E1')  # 正文次级高亮 (Slate-300)

FONT_HEADING = 'Microsoft YaHei'
FONT_BODY = 'Microsoft YaHei'

def set_slide_background(slide, color):
    background = slide.background
    fill = background.fill
    fill.solid()
    fill.fore_color.rgb = color

def add_header(slide, title, category, slide_num):
    # 顶部微标签 (Badge)
    txBox = slide.shapes.add_textbox(Inches(0.8), Inches(0.4), Inches(8.0), Inches(0.4))
    tf = txBox.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
    p = tf.paragraphs[0]
    p.text = f"SOFTWARE ENGINEERING PRACTICE  ·  {category.upper()}"
    p.font.name = FONT_BODY
    p.font.size = Pt(11)
    p.font.bold = True
    p.font.color.rgb = C_PRIMARY

    # 主标题
    txBox2 = slide.shapes.add_textbox(Inches(0.8), Inches(0.75), Inches(10.0), Inches(0.7))
    tf2 = txBox2.text_frame
    tf2.word_wrap = True
    tf2.margin_left = tf2.margin_top = tf2.margin_right = tf2.margin_bottom = 0
    p2 = tf2.paragraphs[0]
    p2.text = title
    p2.font.name = FONT_HEADING
    p2.font.size = Pt(22)
    p2.font.bold = True
    p2.font.color.rgb = C_TEXT_MAIN

    # 页码指示器
    txBox_num = slide.shapes.add_textbox(Inches(11.5), Inches(0.4), Inches(1.0), Inches(0.4))
    tf_num = txBox_num.text_frame
    tf_num.margin_left = tf_num.margin_top = tf_num.margin_right = tf_num.margin_bottom = 0
    p_num = tf_num.paragraphs[0]
    p_num.alignment = PP_ALIGN.RIGHT
    p_num.text = f"{slide_num:02d} / 10"
    p_num.font.name = FONT_BODY
    p_num.font.size = Pt(12)
    p_num.font.bold = True
    p_num.font.color.rgb = C_TEXT_MUTED

def add_card(slide, left, top, width, height, bg_color=C_CARD, border_color=C_BORDER):
    shape = slide.shapes.add_shape(
        MSO_SHAPE.ROUNDED_RECTANGLE,
        Inches(left), Inches(top), Inches(width), Inches(height)
    )
    shape.fill.solid()
    shape.fill.fore_color.rgb = bg_color
    shape.line.color.rgb = border_color
    shape.line.width = Pt(1)
    return shape

def add_card_header(slide, left, top, width, title, tag=None, tag_color=C_PRIMARY):
    txBox = slide.shapes.add_textbox(Inches(left + 0.25), Inches(top + 0.2), Inches(width - 0.5), Inches(0.45))
    tf = txBox.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
    p = tf.paragraphs[0]
    p.text = title
    p.font.name = FONT_HEADING
    p.font.size = Pt(16)
    p.font.bold = True
    p.font.color.rgb = C_TEXT_MAIN

    if tag:
        run = p.add_run()
        run.text = f"  [{tag}]"
        run.font.name = FONT_BODY
        run.font.size = Pt(12)
        run.font.bold = False
        run.font.color.rgb = tag_color

def create_presentation():
    prs = Presentation()
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    blank_layout = prs.slide_layouts[6]

    # =========================================================================
    # SLIDE 1: 封面页 (Title Slide)
    # =========================================================================
    s1 = prs.slides.add_slide(blank_layout)
    set_slide_background(s1, C_BG)

    # 封面外边框装饰卡片
    add_card(s1, 0.8, 0.8, 11.733, 5.9, bg_color=C_CARD_LIGHT, border_color=C_BORDER)

    # 核心大标题与副标题
    tx = s1.shapes.add_textbox(Inches(1.4), Inches(1.3), Inches(10.5), Inches(3.2))
    tf = tx.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0

    p_tag = tf.paragraphs[0]
    p_tag.text = "软件工程技术专业 · 本科课程设计汇报"
    p_tag.font.name = FONT_BODY
    p_tag.font.size = Pt(14)
    p_tag.font.bold = True
    p_tag.font.color.rgb = C_PRIMARY

    p_title = tf.add_paragraph()
    p_title.text = "基于 Java 21 与图拓扑规划的\n高可信 Text2SQL 智能数据问数系统"
    p_title.font.name = FONT_HEADING
    p_title.font.size = Pt(32)
    p_title.font.bold = True
    p_title.font.color.rgb = C_TEXT_MAIN
    p_title.space_before = Pt(14)
    p_title.space_after = Pt(14)

    p_sub = tf.add_paragraph()
    p_sub.text = "—— 软件工程视角下的大模型应用架构设计、质量防御与人机协同研发实践"
    p_sub.font.name = FONT_BODY
    p_sub.font.size = Pt(16)
    p_sub.font.color.rgb = C_TEXT_MUTED

    # 底部元信息卡片
    info_card = add_card(s1, 1.4, 4.8, 10.5, 1.4, bg_color=C_CARD, border_color=C_BORDER)
    tx_info = s1.shapes.add_textbox(Inches(1.7), Inches(5.0), Inches(9.9), Inches(1.0))
    tf_info = tx_info.text_frame
    tf_info.word_wrap = True
    tf_info.margin_left = tf_info.margin_top = tf_info.margin_right = tf_info.margin_bottom = 0

    p_info1 = tf_info.paragraphs[0]
    p_info1.text = "汇报人：江松涛  |  班级：软件工程技术本科班  |  指导教师：答辩评审委员会"
    p_info1.font.name = FONT_BODY
    p_info1.font.size = Pt(14)
    p_info1.font.bold = True
    p_info1.font.color.rgb = C_TEXT_MAIN

    p_info2 = tf_info.add_paragraph()
    p_info2.text = "技术栈：Java 21  ·  Spring Boot 3  ·  PostgreSQL 16  ·  JSqlParser AST  ·  Steiner Tree  ·  DeepSeek"
    p_info2.font.name = FONT_BODY
    p_info2.font.size = Pt(12)
    p_info2.font.color.rgb = C_PRIMARY
    p_info2.space_before = Pt(6)

    # =========================================================================
    # SLIDE 2: 背景与痛点分析 (Problem Definition)
    # =========================================================================
    s2 = prs.slides.add_slide(blank_layout)
    set_slide_background(s2, C_BG)
    add_header(s2, "业务背景与问题定义：大模型裸奔接入的“三大工程陷阱”", "Problem Analysis", 2)

    # 左侧卡片：业务现状
    add_card(s2, 0.8, 1.6, 5.6, 5.2)
    add_card_header(s2, 0.8, 1.6, 5.6, "企业数仓业务现状与传统痛点", "业务端", C_WARN)
    tx = s2.shapes.add_textbox(Inches(1.05), Inches(2.4), Inches(5.1), Inches(4.1))
    tf = tx.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0

    bullets = [
        ("提数链条冗长：", "业务人员提需求 → 数据分析师排期排队 → 编写 SQL，简单指标查询耗时通常长达 2~3 天。"),
        ("数仓拓扑复杂：", "企业级电商数仓包含 37+ 物理表，外键高度交错，业务口径黑话多，非技术人员无法自主查询。"),
        ("核心工程诉求：", "构建高可信自然语言问数系统，实现端到端只读 SQL 转换，将提数周期从天级降至秒级。")
    ]
    for i, (b_title, b_desc) in enumerate(bullets):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(14)
        r1 = p.add_run()
        r1.text = b_title + " "
        r1.font.bold = True
        r1.font.size = Pt(13)
        r1.font.color.rgb = C_TEXT_MAIN
        r2 = p.add_run()
        r2.text = b_desc
        r2.font.size = Pt(13)
        r2.font.color.rgb = C_TEXT_SUB

    # 右侧卡片：直接调用大模型的工程陷阱
    add_card(s2, 6.8, 1.6, 5.7, 5.2)
    add_card_header(s2, 6.8, 1.6, 5.7, "传统裸调 LLM 的三大致命工程陷阱", "工程质量失控", C_DANGER)
    tx = s2.shapes.add_textbox(Inches(7.05), Inches(2.4), Inches(5.2), Inches(4.1))
    tf = tx.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0

    traps = [
        ("陷阱一 · 拓扑断链（Schema 幻觉）：", "涉及 3 表以上关联时，大模型全凭概率猜测外键，极易引入不存在的伪支路与环路。"),
        ("陷阱二 · 性能击穿（Resource Exhaustion）：", "模型极易生成无 Join 条件的隐式笛卡尔积或大表全表扫描，秒级打满生产库 CPU。"),
        ("陷阱三 · 数据越权（Security Escape）：", "存在生成 DDL/DML 篡改数据风险，或利用别名与系统限定名跨库窃取元数据。")
    ]
    for i, (b_title, b_desc) in enumerate(traps):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(14)
        r1 = p.add_run()
        r1.text = b_title + " "
        r1.font.bold = True
        r1.font.size = Pt(13)
        r1.font.color.rgb = C_DANGER
        r2 = p.add_run()
        r2.text = b_desc
        r2.font.size = Pt(13)
        r2.font.color.rgb = C_TEXT_SUB

    # =========================================================================
    # SLIDE 3: 需求工程与质量属性矩阵 (Requirements Engineering)
    # =========================================================================
    s3 = prs.slides.add_slide(blank_layout)
    set_slide_background(s3, C_BG)
    add_header(s3, "需求工程与质量属性矩阵：从功能诉求到非功能工程红线", "Requirements Analysis", 3)

    # 4 个需求象限卡片
    cards_data = [
        ("功能性需求 (Functional)", "语义解析与精准问数", C_PRIMARY, 0.8, 1.6, 5.6, 2.45, [
            ("复杂多表拓扑查询：", "精准支撑 1~5 表链路关联与度量聚合分析；"),
            ("端到端自动生成：", "自然语言全自动生成标准符合 PostgreSQL 方言的 SQL。")
        ]),
        ("安全性属性 (Security)", "100% 只读与沙箱隔离", C_DANGER, 6.8, 1.6, 5.7, 2.45, [
            ("只读语法强审计：", "严禁任何 INSERT / UPDATE / DELETE / DROP 写入行为；"),
            ("元数据访问阻断：", "杜绝 pg_catalog / information_schema 等系统表越权访问。")
        ]),
        ("可靠性与性能 (Performance)", "防止慢查询拖垮集群", C_SUCCESS, 0.8, 4.35, 5.6, 2.45, [
            ("物理执行代价预检：", "在真正下发 SQL 前，通过代价树预先熔断高危查询；"),
            ("二级语法归一化缓存：", "热点查询跳过大模型推理，延迟压降 50% 以上。")
        ]),
        ("经济性约束 (Cost & Usability)", "严控调用成本与工程交付", C_WARN, 6.8, 4.35, 5.7, 2.45, [
            ("超低单次推理成本：", "严格剪枝 Prompt Schema，单次查询成本压低至 0.002 元；"),
            ("无缝集成可观测性：", "全链路耗时、Token 计费、错误归因日志全程透明可追溯。")
        ]),
    ]

    for title, tag, tag_c, left, top, w, h, items in cards_data:
        add_card(s3, left, top, w, h)
        add_card_header(s3, left, top, w, title, tag, tag_c)
        tx = s3.shapes.add_textbox(Inches(left + 0.25), Inches(top + 0.75), Inches(w - 0.5), Inches(h - 0.85))
        tf = tx.text_frame
        tf.word_wrap = True
        tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
        for i, (k, v) in enumerate(items):
            p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
            p.space_after = Pt(6)
            r1 = p.add_run()
            r1.text = "• " + k + " "
            r1.font.bold = True
            r1.font.size = Pt(12)
            r1.font.color.rgb = C_TEXT_MAIN
            r2 = p.add_run()
            r2.text = v
            r2.font.size = Pt(12)
            r2.font.color.rgb = C_TEXT_SUB

    # =========================================================================
    # SLIDE 4: 架构设计：三层纵深防御体系 (System Architecture)
    # =========================================================================
    s4 = prs.slides.add_slide(blank_layout)
    set_slide_background(s4, C_BG)
    add_header(s4, "系统架构设计：三层纵深防御与单一职责解耦模型", "System Architecture", 4)

    # 3个横向阶段卡片，展示数据流转与防御
    layers = [
        ("Layer 1 · 输入前置拓扑规划层", "语义剪枝 & 确定性图算法", C_PRIMARY, 0.8, 1.6, 3.6, 5.2, [
            ("领域词典精准召回：", "双路检索用户 Query 命中的物理表与核心指标字段；"),
            ("斯坦纳树最优路径规划：", "在数仓外键全图中计算连接所有命中表的最小权重 Steiner Tree，补全中间桥接表；"),
            ("动态 Schema 剪枝注入：", "仅把关键表注入 Prompt，将无关表剔除，规避幻觉且大幅降低 Token 消耗。")
        ]),
        ("Layer 2 · 大模型推理与语义翻译", "受控黑盒转换 (DeepSeek)", C_WARN, 4.8, 1.6, 3.6, 5.2, [
            ("角色定位清晰解耦：", "大模型仅作为‘自然语言 → 结构化只读 SQL 语义转换器’，坚决不承担系统安全判断职责；"),
            ("严格输出格式约束：", "强制约束 JSON / SQL 结构体输出，消除多余废话解释；"),
            ("冷热两级归一化缓存：", "若完全命中历史 SQL 语法指纹，直接命中缓存返回，跳过 LLM 推理。")
        ]),
        ("Layer 3 · 输出后置纵深安全防御", "双重物理级防御罩", C_SUCCESS, 8.8, 1.6, 3.7, 5.2, [
            ("防线 A · JSqlParser AST 白名单：", "在 Java 内存中解析语法树，若检测到 DDL/DML 或访问系统表限定名，直接熔断并抛出安全异常；"),
            ("防线 B · PostgreSQL EXPLAIN 预检：", "向物理库发送 EXPLAIN (FORMAT JSON)，递归解析代价树，硬拦截无关联嵌套循环笛卡尔积；"),
            ("防线 C · 数据库只读账号 + 超时：", "最低权限数据库账号连接，强制配置 10 秒查询超时保底。")
        ]),
    ]

    for title, tag, tag_c, left, top, w, h, items in layers:
        add_card(s4, left, top, w, h)
        add_card_header(s4, left, top, w, title, tag, tag_c)
        tx = s4.shapes.add_textbox(Inches(left + 0.25), Inches(top + 0.75), Inches(w - 0.5), Inches(4.2))
        tf = tx.text_frame
        tf.word_wrap = True
        tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
        for i, (k, v) in enumerate(items):
            p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
            p.space_after = Pt(10)
            r1 = p.add_run()
            r1.text = "• " + k + "\n  "
            r1.font.bold = True
            r1.font.size = Pt(12)
            r1.font.color.rgb = C_TEXT_MAIN
            r2 = p.add_run()
            r2.text = v
            r2.font.size = Pt(11)
            r2.font.color.rgb = C_TEXT_SUB

    # =========================================================================
    # SLIDE 5: 核心算法工程：斯坦纳树路径规划 (Core Algorithm)
    # =========================================================================
    s5 = prs.slides.add_slide(blank_layout)
    set_slide_background(s5, C_BG)
    add_header(s5, "核心算法工程：基于斯坦纳树（Steiner Tree）的外键路径规划", "Algorithm Engineering", 5)

    # 左侧对比方案
    add_card(s5, 0.8, 1.6, 5.6, 5.2)
    add_card_header(s5, 0.8, 1.6, 5.6, "方案对比：为什么局部贪心算法会崩溃？", "架构权衡", C_WARN)
    tx = s5.shapes.add_textbox(Inches(1.05), Inches(2.4), Inches(5.1), Inches(4.2))
    tf = tx.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0

    comp_items = [
        ("被否决方案 · 贪心 BFS / 最短路：", "数仓中经常存在‘环形外键’或‘多路径冗余关联’。单纯最短路径或贪心搜索会陷入局部最优，引入大量无关孤岛表，导致 Join 断链。"),
        ("入选方案 · 斯坦纳树近似算法：", "将 37 张表视作图节点，外键关系作为加权边。用户命中的表作为终端节点（Terminals），求解包含所有终端节点的最小连通子树。"),
        ("数学防挤压与权重设计：", "直接命中表赋予基础权重，扩展伴生表施加 0.4 强衰减系数，确保核心目标永远排在 Top-K 前列，绝不发生劣币驱逐良币。")
    ]
    for i, (k, v) in enumerate(comp_items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(12)
        r1 = p.add_run()
        r1.text = k + "\n"
        r1.font.bold = True
        r1.font.size = Pt(13)
        r1.font.color.rgb = C_TEXT_MAIN
        r2 = p.add_run()
        r2.text = v
        r2.font.size = Pt(12)
        r2.font.color.rgb = C_TEXT_SUB

    # 右侧：量化收益指标
    add_card(s5, 6.8, 1.6, 5.7, 5.2)
    add_card_header(s5, 6.8, 1.6, 5.7, "斯坦纳树算法带来的确定性量化增益", "实测效果", C_SUCCESS)

    # 3 个数据指标块
    stats = [
        ("99.00%", "表检索全召回率", "在 200 题基准评测集中，准确捕获目标拓扑关键表"),
        ("100.0%", "3 表关联成功率", "彻底消除跨用户、订单、明细表的伪支路幻觉"),
        ("90.91%", "4 表及以上复杂查询准确率", "大幅突破传统 Text2SQL 在深度多表链路中的瓶颈")
    ]
    for idx, (num, label, desc) in enumerate(stats):
        top_offset = 2.4 + idx * 1.35
        # 内部小卡片
        add_card(s5, 7.1, top_offset, 5.1, 1.2, bg_color=C_CARD_LIGHT, border_color=C_BORDER)
        tx = s5.shapes.add_textbox(Inches(7.25), Inches(top_offset + 0.15), Inches(4.8), Inches(0.9))
        tf = tx.text_frame
        tf.word_wrap = True
        tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
        p = tf.paragraphs[0]
        r_num = p.add_run()
        r_num.text = num + "  "
        r_num.font.bold = True
        r_num.font.size = Pt(22)
        r_num.font.color.rgb = C_PRIMARY
        r_lab = p.add_run()
        r_lab.text = label
        r_lab.font.bold = True
        r_lab.font.size = Pt(14)
        r_lab.font.color.rgb = C_TEXT_MAIN

        p2 = tf.add_paragraph()
        p2.text = desc
        p2.font.size = Pt(11)
        p2.font.color.rgb = C_TEXT_MUTED
        p2.space_before = Pt(3)

    # =========================================================================
    # SLIDE 6: 安全与防御工程：AST 审计与 EXPLAIN 熔断 (Defensive Engineering)
    # =========================================================================
    s6 = prs.slides.add_slide(blank_layout)
    set_slide_background(s6, C_BG)
    add_header(s6, "安全与可靠性工程：AST 语法白名单与 EXPLAIN 物理拦截", "Defensive Engineering", 6)

    # 卡片 1: JSqlParser 静态语法树审计
    add_card(s6, 0.8, 1.6, 5.6, 5.2)
    add_card_header(s6, 0.8, 1.6, 5.6, "JSqlParser AST 静态语法树审计", "防线一 · 代码级", C_PRIMARY)
    tx = s6.shapes.add_textbox(Inches(1.05), Inches(2.4), Inches(5.1), Inches(4.2))
    tf = tx.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0

    ast_points = [
        ("根节点严格白名单：", "仅放行 PlainSelect 结构，任何 DDL（CREATE/DROP）、DML（INSERT/UPDATE）以及多语句分号拼接，在语法分析阶段直接判定为非法。"),
        ("表名限定名穿透防御：", "递归解析 AST 上的所有 Table 节点，严密阻断通过 pg_catalog. 或 information_schema. 前缀越权嗅探数据库元数据的逃逸攻击。"),
        ("别名欺骗与深层漏洞修复：", "在研发过程中排查出利用子查询别名隐藏敏感表的 3 处安全漏洞，通过深度优先遍历语法树彻底修复封死。")
    ]
    for i, (k, v) in enumerate(ast_points):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(12)
        r1 = p.add_run()
        r1.text = k + "\n"
        r1.font.bold = True
        r1.font.size = Pt(13)
        r1.font.color.rgb = C_TEXT_MAIN
        r2 = p.add_run()
        r2.text = v
        r2.font.size = Pt(12)
        r2.font.color.rgb = C_TEXT_SUB

    # 卡片 2: PostgreSQL EXPLAIN 物理代价拦截
    add_card(s6, 6.8, 1.6, 5.7, 5.2)
    add_card_header(s6, 6.8, 1.6, 5.7, "PostgreSQL EXPLAIN 原生执行计划预检", "防线二 · 物理级", C_DANGER)
    tx = s6.shapes.add_textbox(Inches(7.05), Inches(2.4), Inches(5.2), Inches(4.2))
    tf = tx.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0

    explain_points = [
        ("执行计划树递归解析：", "下发 EXPLAIN (FORMAT JSON, COSTS ON)，解析返回的计划树，计算整棵树的 Total Cost 与预估行数。"),
        ("隐式笛卡尔积精准硬拦截：", "重点识别节点 Join Type 为 Nested Loop 且缺乏有效 Join Filter 条件的情况，在扫描大表前实施物理熔断。"),
        ("故障左移（Shift Left）设计思想：", "传统依赖 10s 查询超时属于事后止损；EXPLAIN 预检耗时仅数毫秒，杜绝慢查询进入磁盘 IO 与 Buffer Pool，保障生产库平稳。")
    ]
    for i, (k, v) in enumerate(explain_points):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(12)
        r1 = p.add_run()
        r1.text = k + "\n"
        r1.font.bold = True
        r1.font.size = Pt(13)
        r1.font.color.rgb = C_TEXT_MAIN
        r2 = p.add_run()
        r2.text = v
        r2.font.size = Pt(12)
        r2.font.color.rgb = C_TEXT_SUB

    # =========================================================================
    # SLIDE 7: AI 辅助软件工程：人机协同研发全景 (AI-Native SE)
    # =========================================================================
    s7 = prs.slides.add_slide(blank_layout)
    set_slide_background(s7, C_BG)
    add_header(s7, "AI 辅助软件工程：我们如何建立人机契约与增量演进", "Human-AI Co-Engineering", 7)

    # 3 个阶段性卡片展示人机协同实践
    ai_cards = [
        ("1. 建立工程契约 (AGENTS.md)", "拒绝无序闲聊，规范先行", C_PRIMARY, 0.8, 1.6, 3.6, 5.2, [
            ("严禁客套与虚假安全感：", "在根目录确立人机协作铁律，AI 必须像挑剔的资深架构师直击代码不足，杜绝盲目迎合。"),
            ("强制交付技术与设计导读：", "写完代码必须交代机制、数据流、标记追踪（如 directHits vs expandedTables）与边界防线。"),
            ("版本必须先跑通：", "先只生成 pom.xml 并验证依赖可用，再写业务代码，杜绝幻觉版本号。")
        ]),
        ("2. 增量迭代与敏捷演进", "单兵敏捷 Sprint 闭环", C_WARN, 4.8, 1.6, 3.6, 5.2, [
            ("微阶段粒度交付：", "绝不让 AI 一次性生成整个庞大工程。一次只推进一个具体模块（如仅做 AST 语法解析）。"),
            ("严密版本控制 (Git Commit)：", "每完成一个阶段、每通过一批测试，必须立即执行 Git commit，确保每个步骤都有代码沉淀与回滚点。"),
            ("架构决策人类把关：", "AI 提供候选实现，人类工程师基于设计模式与复杂度做出最终技术决策。")
        ]),
        ("3. 对抗式测试与漏洞修复", "人类充当红队审计官", C_SUCCESS, 8.8, 1.6, 3.7, 5.2, [
            ("构造对抗渗透用例：", "人类工程师针对 AI 生成的代码构造 53 条恶意攻击用例，当场检测出 3 处 AST 别名逃逸缺陷。"),
            ("逼迫 AI 深度重构：", "用失败用例作为输入反馈给 AI，督促其完成防御边界加固与二次代码重构。"),
            ("研发角色深刻转变：", "人类工程师从键盘‘打字员’转变为‘系统架构师、验收法官与安全红队’。")
        ]),
    ]

    for title, tag, tag_c, left, top, w, h, items in layers:
        # 重用卡片生成逻辑
        pass

    for title, tag, tag_c, left, top, w, h, items in ai_cards:
        add_card(s7, left, top, w, h)
        add_card_header(s7, left, top, w, title, tag, tag_c)
        tx = s7.shapes.add_textbox(Inches(left + 0.25), Inches(top + 0.75), Inches(w - 0.5), Inches(4.2))
        tf = tx.text_frame
        tf.word_wrap = True
        tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
        for i, (k, v) in enumerate(items):
            p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
            p.space_after = Pt(10)
            r1 = p.add_run()
            r1.text = "• " + k + "\n  "
            r1.font.bold = True
            r1.font.size = Pt(12)
            r1.font.color.rgb = C_TEXT_MAIN
            r2 = p.add_run()
            r2.text = v
            r2.font.size = Pt(11)
            r2.font.color.rgb = C_TEXT_SUB

    # =========================================================================
    # SLIDE 8: 质量保障与测试闭环 (Quality Assurance)
    # =========================================================================
    s8 = prs.slides.add_slide(blank_layout)
    set_slide_background(s8, C_BG)
    add_header(s8, "质量保障体系：自动化测试网与数据治理闭环", "Quality Assurance", 8)

    # 左侧：三层测试金字塔
    add_card(s8, 0.8, 1.6, 5.6, 5.2)
    add_card_header(s8, 0.8, 1.6, 5.6, "三层严密测试防护网（全部通过）", "测试体系", C_PRIMARY)

    test_stats = [
        ("279 项", "自动化回归测试", "覆盖 AST 语法分析、模型客户端连接、图算法、缓存淘汰策略"),
        ("53 条", "对抗渗透与越权用例", "针对 DDL、跨库查询、别名逃逸、慢查询进行高强度红队对抗验证"),
        ("200 题", "真实数仓基准评测集", "覆盖 6 层由浅入深难度的完整数仓业务场景，同模型下对照实验")
    ]
    for idx, (num, label, desc) in enumerate(test_stats):
        top_offset = 2.4 + idx * 1.35
        add_card(s8, 1.05, top_offset, 5.1, 1.2, bg_color=C_CARD_LIGHT, border_color=C_BORDER)
        tx = s8.shapes.add_textbox(Inches(1.2), Inches(top_offset + 0.15), Inches(4.8), Inches(0.9))
        tf = tx.text_frame
        tf.word_wrap = True
        tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
        p = tf.paragraphs[0]
        r_num = p.add_run()
        r_num.text = num + "  "
        r_num.font.bold = True
        r_num.font.size = Pt(20)
        r_num.font.color.rgb = C_SUCCESS
        r_lab = p.add_run()
        r_lab.text = label
        r_lab.font.bold = True
        r_lab.font.size = Pt(13)
        r_lab.font.color.rgb = C_TEXT_MAIN

        p2 = tf.add_paragraph()
        p2.text = desc
        p2.font.size = Pt(11)
        p2.font.color.rgb = C_TEXT_MUTED
        p2.space_before = Pt(3)

    # 右侧：日志归因与数据治理
    add_card(s8, 6.8, 1.6, 5.7, 5.2)
    add_card_header(s8, 6.8, 1.6, 5.7, "缺陷归因闭环与评测集数据治理", "工程闭环", C_WARN)
    tx = s8.shapes.add_textbox(Inches(7.05), Inches(2.4), Inches(5.2), Inches(4.2))
    tf = tx.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0

    qa_points = [
        ("科学的四层错误归因分析：", "每次测试失败均做精准定责：是表选错、字段选错、Join 连接错、还是业务口径冲突？拒绝‘盲猜调参’。"),
        ("评测集数据治理反哺：", "通过执行日志深度复盘，定位到基准集多处由于历史遗留导致的口径冲突，在用户问题文本零修改的前提下治理标注噪声。"),
        ("完善的工程资产沉淀：", "形成了完整的 DEFECT_LOG.md 缺陷台账与 FAILURE_ATTRIBUTION.md 归因报告，展现正规软件工程工程素养。")
    ]
    for i, (k, v) in enumerate(qa_points):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(12)
        r1 = p.add_run()
        r1.text = k + "\n"
        r1.font.bold = True
        r1.font.size = Pt(13)
        r1.font.color.rgb = C_TEXT_MAIN
        r2 = p.add_run()
        r2.text = v
        r2.font.size = Pt(12)
        r2.font.color.rgb = C_TEXT_SUB

    # =========================================================================
    # SLIDE 9: 工程度量与成效 (Software Metrics)
    # =========================================================================
    s9 = prs.slides.add_slide(blank_layout)
    set_slide_background(s9, C_BG)
    add_header(s9, "工程度量与量化成效：用严谨的基准数据说话", "Engineering Metrics", 9)

    # 4 个指标大卡片
    metric_cards = [
        ("端到端执行准确率", "从 62% 跃升至 96.00%", "96.00%", "+34.00%", C_SUCCESS, 0.8, 1.6, 5.6, 2.45,
         "在 200 题真实数仓基准测试中，同模型（DeepSeek）对照下，相比 Naive Baseline 实现质的飞跃。"),
        ("SQL 物理有效执行率", "语法与执行错误彻底归零", "100%", "0 报错", C_PRIMARY, 6.8, 1.6, 5.7, 2.45,
         "生成的每一条只读 SQL 均严格符合 PostgreSQL 语法与执行规范，未发生任何数据库执行崩溃。"),
        ("系统延迟与 Token 优化", "二级规范化缓存赋能", "-53%", "Token -50%", C_WARN, 0.8, 4.35, 5.6, 2.45,
         "通过 SQL AST 语法归一化与指纹哈希匹配，热点高频查询跳过模型推理，响应延迟降低 53%。"),
        ("单次查询平均成本", "极致经济性与商用可能", "0.002 元", "极低成本", C_PRIMARY, 6.8, 4.35, 5.7, 2.45,
         "得益于斯坦纳树精确剪枝 Schema 与关停无效重试，将 Token 消耗控制在极限水平，单次仅需两厘钱。")
    ]

    for title, subtitle, stat_main, stat_sub, col, left, top, w, h, desc in metric_cards:
        add_card(s9, left, top, w, h)
        add_card_header(s9, left, top, w, title, subtitle, col)
        tx = s9.shapes.add_textbox(Inches(left + 0.25), Inches(top + 0.75), Inches(w - 0.5), Inches(h - 0.85))
        tf = tx.text_frame
        tf.word_wrap = True
        tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
        p = tf.paragraphs[0]
        r1 = p.add_run()
        r1.text = stat_main + "  "
        r1.font.bold = True
        r1.font.size = Pt(24)
        r1.font.color.rgb = col
        r2 = p.add_run()
        r2.text = f"({stat_sub})"
        r2.font.bold = True
        r2.font.size = Pt(14)
        r2.font.color.rgb = C_TEXT_MUTED

        p2 = tf.add_paragraph()
        p2.text = desc
        p2.font.size = Pt(12)
        p2.font.color.rgb = C_TEXT_SUB
        p2.space_before = Pt(6)

    # =========================================================================
    # SLIDE 10: 工程反思与总结 (Retrospective & Conclusion)
    # =========================================================================
    s10 = prs.slides.add_slide(blank_layout)
    set_slide_background(s10, C_BG)
    add_header(s10, "工程反思与总结：科学证伪决策与工程师角色进化", "Conclusion & Retrospective", 10)

    # 左侧：科学证伪决策（亮点）
    add_card(s10, 0.8, 1.6, 5.6, 5.2)
    add_card_header(s10, 0.8, 1.6, 5.6, "经典工程证伪实践：主动砍掉‘启发式重试’", "工程决策", C_WARN)
    tx = s10.shapes.add_textbox(Inches(1.05), Inches(2.4), Inches(5.1), Inches(4.2))
    tf = tx.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0

    retro_points = [
        ("初始技术假设：", "最初设计中，期望在 SQL 失败时将报错信息反哺给模型进行自动重试修复。"),
        ("实测数据证伪：", "在真实 200 题评测中，实测发现启发式盲猜重试的纠错成功率接近于 0，反而使网络延迟和 Token 消耗翻倍。"),
        ("果断止损做减法：", "绝不为了‘功能多’而保留无效代码，果断在架构中下线该模块，消除了无效成本。这体现了软件工程尊重实测数据的求实精神。")
    ]
    for i, (k, v) in enumerate(retro_points):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(12)
        r1 = p.add_run()
        r1.text = k + "\n"
        r1.font.bold = True
        r1.font.size = Pt(13)
        r1.font.color.rgb = C_TEXT_MAIN
        r2 = p.add_run()
        r2.text = v
        r2.font.size = Pt(12)
        r2.font.color.rgb = C_TEXT_SUB

    # 右侧：总结与未来展望
    add_card(s10, 6.8, 1.6, 5.7, 5.2)
    add_card_header(s10, 6.8, 1.6, 5.7, "软件工程思想启示与开源沉淀", "总结展望", C_PRIMARY)
    tx = s10.shapes.add_textbox(Inches(7.05), Inches(2.4), Inches(5.2), Inches(4.2))
    tf = tx.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0

    conc_points = [
        ("软件工程师的角色进化：", "在 AI 时代，程序员不再是简单的代码打字员，而是系统的‘需求拆解者、架构裁决官与质量红队’。严谨的软件工程方法论是驾驭 AI 的基石。"),
        ("开源与工程资产沉淀：", "全套架构设计、279 项自动化测试套件与评测体系已全部开源于 GitHub（github.com/xunqian99/Text2SQL-agent）。"),
        ("未来演进方向：", "下一步计划将静态 Schema 字典升级为语义指标层（Semantic Layer），实现数仓指标维度的 DSL 统一纳管。")
    ]
    for i, (k, v) in enumerate(conc_points):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(12)
        r1 = p.add_run()
        r1.text = k + "\n"
        r1.font.bold = True
        r1.font.size = Pt(13)
        r1.font.color.rgb = C_TEXT_MAIN
        r2 = p.add_run()
        r2.text = v
        r2.font.size = Pt(12)
        r2.font.color.rgb = C_TEXT_SUB

    # 保存 PPTX 文件
    output_path = os.path.abspath("docs/presentation/Text2SQL_Course_Design_Presentation.pptx")
    prs.save(output_path)
    print(f"[SUCCESS] PPTX generated at: {output_path}")

if __name__ == '__main__':
    create_presentation()
