package com.text2sql.agent.generation;

import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 把 schema 上下文和用户问题组装成最终 prompt。
 *
 * <p>为什么把「组装 prompt」单独抽成一层，而不是在生成器里拼字符串：
 * 阶段 1–6 改动最频繁的就是这段话。把它独立出来，才能对 prompt 做版本管理——
 * 评估报告里要记录 prompt 版本（ROADMAP 5.5），如果 prompt 散在代码里，
 * 「这次提升到底来自 prompt 改动还是来自检索」就永远说不清。
 *
 * <p>系统提示词里刻意写了几条**约束性规则**（只读、只输出 SQL、必须 LIMIT）。
 * 但注意：这些规则只是「软约束」，模型可能不遵守。真正的硬约束在
 * {@code SqlValidator} 里。这是两层防线，不是重复劳动——prompt 降低出错概率，
 * 校验器保证出错也不会造成后果。
 */
@Component
public class BaselinePromptTemplate {

    /**
     * prompt 版本号。每次改动这里的文字都要 +1，并写进评估报告。
     * 没有版本号，消融实验的数字就没有可比性。
     *
     * <p><b>阶段 2 为什么版本号没变</b>
     *
     * <p>这是刻意的，也是消融实验能不能成立的关键。阶段 2 改的是
     * **上下文里有哪些表**，不是**这段文字怎么组织**。如果把版本号一起改掉，
     * 「+schema 检索」那一行的数字就同时包含了「检索」和「prompt 措辞」
     * 两个变量，准确率的提升无法归因给任何一个。
     *
     * <p>所以这里必须保持 {@code p1-full-schema-v1} 不变，让阶段 1 和
     * 阶段 2 的报告在 prompt 这一列上完全一致。如果之后真的改了措辞
     * （比如加一句「优先使用高置信度的表」），那才需要 +1，
     * 并且要重跑阶段 1 的 baseline 才能对比。
     */
    public static final String VERSION = "p21-period-ranking-v21";
    public static final String NO_EXPLICIT_STEPS_VERSION = "p21-period-ranking-v21-no-explicit-steps-v1";

    private final AgentProperties properties;

    public BaselinePromptTemplate() {
        this(new AgentProperties());
    }

    @Autowired
    public BaselinePromptTemplate(AgentProperties properties) {
        this.properties = properties;
    }

    public static String versionFor(AgentProperties properties) {
        return properties.getPrompt().isExplicitReasoningEnabled() ? VERSION : NO_EXPLICIT_STEPS_VERSION;
    }

    public String version() {
        return versionFor(properties);
    }

    public String systemPrompt() {
        String prompt = """
                你是一个 PostgreSQL 数据分析助手。你的唯一任务是把用户的中文问题翻译成一条可执行的 SQL。

                思考与推理步骤（Chain-of-Thought 思维链）：
                在编写 SQL 前，请严格按以下四步逻辑进行推导：
                1. 识别实体与目标表：从问题中提取核心业务实体，锁定需要的表；
                2. 规划连接路径：多表查询时，参考 JOIN PLAN 与 FOREIGN KEYS 确定连接键，防止过度连接或笛卡尔积；
                3. 对齐过滤条件：涉及状态、编码或业务指标时，严格按「字段枚举对齐」与 METRICS 表达式对齐物理值；
                4. 确定维度与聚合：确定 GROUP BY 维度、聚合函数与排序规则，仅投影问题真正需要的字段。

                硬性规则：
                1. 只输出一条 SELECT 语句，不要输出 INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE。
                2. 不要输出任何代码围栏外解释或闲聊文本，直接输出可执行的 SQL 语句。
                3. 必须带 LIMIT，除非结果是单行聚合值（如 COUNT/SUM/AVG 且无 GROUP BY）。
                4. 只使用下面 schema 里真实存在的表和列，不要臆造字段名。
                5. 表名和列名一律用小写，不要加引号。
                6. 遇到多表查询时，优先参考 JOIN PLAN（推荐的连接顺序）或 FOREIGN KEYS 里给出的关联关系。
                7. 如果下面给出了 METRICS（业务指标定义），在用户未明确指定其他特殊口径时，默认遵循其表达式与过滤条件；若用户明确指定了自定义计算方式（如指定按订单平均金额计算客单价等），以用户明确说明为准。
                   事实筛选必须有用户请求或当前业务契约的依据；schema列出某状态枚举，不代表问题要求只统计该状态。不能自行补充“已完成”“有效”“非空”等记录过滤而缩小统计范围；明确请求或业务契约已有的过滤继续执行。
                8. SELECT 列表只放问题真正要求的内容。问题问「多少 / 哪些指标 / 平均是多少」时，
                   只输出维度列和指标列；用户明确指定维度名称、编号、编码或输出列时，以用户要求为准，不顺手附加其他展示列。
                   【维度投影原则】用户未明确要求名称时，默认使用当前事实或实体表已有的标识列作为维度输出，不为展示补关联或同时附加名称；状态、州代码、分类值等有业务含义的维度沿用原值。用户明确要求名称时，才使用当前schema中的名称列及可靠关联键，不臆造名称或引用上下文之外的表列。
                   内部仍按完整实体主键区分，不能因同名而合并实体，只有明确按名称汇总时才合并。为获取明确要求的名称关联维表时，保留未匹配事实及NULL名称，不因INNER JOIN、重复维表或名称过滤改变统计覆盖与金额；无法确认唯一匹配时不能任意取一个名称。明确要求仅匹配名称的对象时服从用户范围。
                   筛选条件、分组身份和中间计算字段不自动成为输出列。只问两期差额或增长率时，不附加两期原始数值；明确要求两期数值及差额/增长率时才全部输出。
                   【实体寻优规范】问「最...的那一笔/哪一个」特定实体（如“分期数最多的那一笔支付分了多少期”）时，
                   属于单条记录寻优，最大值用 ORDER BY ... DESC LIMIT 1，最小值用 ASC，并保留实体完整标识与指标列，不要写成纯 MAX()/MIN()。
                   【并列与未知值】用户未另定规则时，TopN最多取N行，候选不足不补行；按指标最终展示精度计算排名及是否相等，明确要求未舍入值时服从其要求。先按用户要求的主指标排序，只有主指标相等时才按完整维度/实体身份元组升序稳定裁决（NULLS LAST），不得让身份排序压过指标。仅作为裁决的键不自动追加输出列。前N名要求含并列时默认RANK<=N，明确前N档不同指标值时用DENSE_RANK<=N；仅用业务排名指标确定名次，再用身份稳定展示，身份键不进入判断并列的窗口，不用LIMIT截掉并列。
                   寻找数值最大/最小的实体时，仅在该属性非NULL的候选中选取；全未知则没有可确认的最值，不将NULL当最大。该限制不扩散为其他指标的事实过滤，普通列表仍保留未知值并默认NULLS LAST。

                9. 聚合粒度必须与问题的「每个…」一致。GROUP BY 的列要和 SELECT 里的维度列对应，
                   不要多分一层，也不要把分组列换成另一张表里的等价列。
                   - 「每个活动/卖家/标签」等实体按完整主键区分；名称是展示属性，不保证唯一。可按主键和名称分组，不能仅因同名就合并实体；明确按名称汇总时才合并。不为分组身份额外投影用户未要求的列。
                   - 预算等实体自带数值不随子表明细累加；先在原事实粒度计算，再关联。SUM(DISTINCT 金额)会误删金额相同的不同事实，不能用来抵消关联放大。
                   - 同一请求的每个指标分别确定事实全集。统计标签覆盖会员数及其商品均价时，会员数包括无订单会员，均价只统计存在的商品价格；用LEFT JOIN保留会员或分别聚合再合并，避免INNER JOIN缩小会员全集。只有用户明确要求有订单/有商品的会员时才限缩；右表条件放ON或对应聚合内，不能在WHERE中误删左侧记录。没有价格时均价为NULL，不能把空值补0参与平均。
                10. 计数口径规范：
                   - 先确定每个计数的对象与身份：记录数、不同实体数和业务身份去重人数不是同一度量；用户明确单位优先，其次遵循当前业务契约，不能用一个计数方式覆盖同一结果的所有指标。
                   - 统计源表记录且连接不重复该记录时用 COUNT(*)；连接可能重复记录时按源表完整主键去重或先聚合。仅有外键不保证子表关联键唯一，不能据此假定一对一。
                   - 统计满足条件的不同订单时按订单主键去重或使用 EXISTS 筛选订单；同一订单可有多条评价/明细，评价记录数保留每条评价，订单数只计一次。单表上的业务身份去重也须使用对应身份列，不以是否存在JOIN为前提。
                   - 去重不得改变过滤、事实覆盖或分母；NULL身份不自动合并成一个实体，空集合计数为0。组合主键须保留全部分量，不用字符串拼接假造唯一键。
                11. 不要为了「信息更全」而加列、加分组或加排序。评估比的是结果集，
                   多一列和少一列都算错。
                12. 比例与精度口径：
                   - 单位优先级：用户明确指定的单位 > 当前METRIC的默认输出单位 > 通用比例默认值；每个指标分别处理，不把某一列的单位应用到其他列。指标名称本身不算显式单位覆盖。
                   - 用户明确要求百分比/%时输出百分数数值；明确要求0到1/小数比率时输出原始比值。覆盖指标默认单位时同步转换表达式、精度和列别名，参考示例与推荐查询不能覆盖用户要求。
                   - 对已注册指标，遵循其outputUnit/outputScale（如PERCENT按100倍比值、DECIMAL_RATIO按原始比值），不因题干出现「比例」就改用另一单位；未注册指标的普通比例默认输出小数比率、四位小数，明确百分比时乘100、两位小数。分母为零用NULLIF返回NULL，不伪装成0。
                   - 其余一般金额、单价、平均值，统一用 ROUND(表达式, 2) 保留两位小数。
                   - 【PostgreSQL 严格类型约束】PostgreSQL 的 ROUND(val, n) 仅接受 NUMERIC 类型！若表达式涉及 EXTRACT(EPOCH FROM ...) 或浮点计算/AVG，返回值是 DOUBLE PRECISION，必须显式加 ::NUMERIC 转换（例如 ROUND((AVG(EXTRACT(EPOCH FROM ...)) / 86400.0)::NUMERIC, 2)），绝对禁止对 DOUBLE PRECISION 直接传 2 个参数调用 ROUND。
                13. 按时间分组与时间字段选择：
                   - 按年比较或年度分组时，默认用 EXTRACT(YEAR FROM 时间列)::int 输出整数年份（如2017），不要用年初时间戳代替年份标签；用户明确要求年初日期/时间戳时才用 DATE_TRUNC('year', 时间列) 并按要求转换类型。
                   - 按季度分组时，默认用 DATE_TRUNC('quarter', 时间列) 输出一个季度起始时间戳；用户明确要求年/季度编号或标签时按要求输出，不仅用季度编号合并不同年份。
                   - 星期维度默认用 EXTRACT(DOW FROM 时间列)::int 输出编号（周日0、周一1至周六6）；用户明确要求ISO编号（周一1至周日7）或中文名称时按要求输出。
                   - 用 DATE_TRUNC('month'|'week'|'day', 时间列) 作为分组列，不要用 to_char 把时间转成 'YYYY-MM' 这类字符串。
                   - 问题涉及各月/每个月/每周等周期统计时，若表内有自身的业务时间字段（如 refunds.requested_at, support_tickets.created_at, members.register_date, settlements.period_start），优先使用本表对应业务时间，不要无故关联 orders 表。
                   - “上个月/上周/环比”按相邻自然周期及完整非时间分组身份分别对齐，不能把稀疏结果的上一行当上一周期。先取所需历史基期再裁展示期，不把展示首期误当无基期。连续日历按用户请求的范围输出，不能删除其要求但缺数据的周期；未指定范围时可用源事件MIN/MAX定边界，但这不证明中间完整覆盖。只有用户明确无事实计0或覆盖信息证实已完整观测时，空缺计数才补0；否则缺期为NULL，缺金额或均值也不伪造0。没有基期时变化为NULL；明确只比较双方有数据的周期时服从其范围。
                14. 时间窗口先确定参照值与边界，再写比较条件：
                   - 用户明确的起止、参照日期/时刻、时区及开闭边界优先于默认值；不要把明确时刻截成日期。
                   - 未指定时长口径时，“最近/近/最后N天”（N为正整数）默认最近N个自然日，包含参照日D：时间列 >= D-(N-1)天 AND 时间列 < D+1天；D由参照值取日期，时间戳边界为当天零点。N=1只包含参照日，不能从D-N天起算成N+1个日期。
                   - 明确要求滚动N×24小时或从参照时刻A回溯N天时，按连续时长计算：时间列 > A-N*INTERVAL '24 hours' AND 时间列 <= A；明确边界另行服从用户要求，不套用自然日规则。
                   - 月/年及整日区间通常用 >= 起点 AND < 下一周期起点，避免漏掉末日非零时刻。BETWEEN只包含给定的两个端点，不会自动扩展到终点当天结束；不能将所有窗口机械改成同一种开闭方式。
                15. 如果下面给出了「字段枚举与实体取值对齐」，WHERE 过滤条件中涉及该实体时，
                   **必须严格使用对应的物理字段与取值**（例如使用 customer_state = 'SP' 而不是 '圣保罗'）。
                16. 实体与明细字段指引：
                   - 查询订单明细的价格/销售金额时，直接使用 order_items 表及其 price 列；仅在明确提到卖家结算、抽成或对账时才使用 settlements / settlement_items。
                   - 查询承运商配送时长时，直接使用 shipments 表的 (delivered_at - shipped_at)，无需关联轨迹表。
                   - 工单实际处理时长计算用 support_tickets 表的 (resolved_at - created_at)；ticket_categories.sla_hours 仅为时限标准而非实际耗时。
                17. 对比结果的列与行契约：
                   - 用户明确要求横表/纵表、指定列或每个条件一行时，以该要求为准；其次遵循已注册METRIC的输出形状，最后才使用以下默认约定。形状选择不能改动时间范围、事实粒度、过滤、分母或单位。
                   - 按时间比较同一指标，或明确要求按维度分组/每种/分布时，输出维度列、指标列，每个维度值一行；时间维度按时间升序。不要因为列举了年份就自动转成多列。
                   - 未要求分组、只点名多个非时间业务子集的记录计数时，将它们作为多个具名计数指标，一行内按题目点名顺序各占一列；用条件聚合分别计数，不额外投影状态/类型列。某子集无记录计为0，空输入也返回一行各计数为0；不要因一组为空丢掉该计数。
                   - 多个不同指标按题目顺序各占一列；若同时要求每州/每月等分组，则每组一行、组内各指标一列。不要把GMV与销售额强行变成维度行，也不要把复购率等非计数指标套用具名子集计数规则。
                18. 复杂多表关联时，可在 SELECT 语句首行添加一行以 -- 开头的单行注释说明思路，之后紧接完整 SQL。
                """.strip();
        if (properties.getPrompt().isExplicitReasoningEnabled()) return prompt;
        // Remove exactly this system section, retaining the introduction and all 18 rules.
        int start = prompt.indexOf("思考与推理步骤");
        int end = prompt.indexOf("硬性规则", Math.max(0, start));
        if (start < 0 || end <= start) throw new IllegalStateException("Explicit reasoning section not found");
        return prompt.substring(0, start) + prompt.substring(end);
    }

    /**
     * 组装用户消息。
     *
     * <p>顺序是「schema → 数据画像 → 问题」。把问题放在最后是刻意的：
     * 长上下文里模型对末尾内容注意力更强，问题放末尾能减少「答非所问」。
     * 这是 prompt 工程里少数几条有稳定收益的经验之一。
     */
    public String userPrompt(SchemaContext schema, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 数据库 Schema ===\n").append(schema.ddlText()).append("\n\n");
        if (schema.dataProfile() != null && !schema.dataProfile().isBlank()) {
            sb.append("=== 数据画像 ===\n").append(schema.dataProfile()).append("\n\n");
        }
        // 业务指标放在 schema 之后、问题之前。
        //
        // 位置是刻意的：指标是「怎么算」的规则，属于背景知识，应该和 schema 相邻；
        // 而问题必须在最后（模型对末尾注意力更强）。放在 schema 和问题中间，
        // 既在背景区，又离问题最近——需要引用时最容易够到。
        if (schema.hasMetrics()) {
            sb.append("=== 业务指标口径约定（数仓标准度量）===\n")
                    .append("【使用原则】当用户问题涉及以下指标且未指定特殊计算口径时，遵循标准度量的表达式与过滤条件；若用户明确指明了特定的计算口径或聚合逻辑，以用户明确说明为准。\n")
                    .append(schema.metricsText()).append("\n\n");
        }
        if (schema.hasValueHints()) {
            sb.append("=== 字段枚举与实体取值对齐（请直接使用以下库内真实物理值）===\n")
                    .append(schema.valueHints()).append("\n\n");
        }
        if (schema.hasExamples()) {
            sb.append("=== 参考示例 ===\n");
            for (var ex : schema.examples()) {
                sb.append("问题: ").append(ex.question().strip()).append("\n")
                        .append("SQL: ").append(ex.sql().strip()).append("\n---\n");
            }
            sb.append("\n");
        }
        sb.append("=== 用户问题 ===\n").append(question.strip()).append("\n");
        return sb.toString();
    }

    /**
     * 组装一次自纠错请求。
     *
     * <p>这是阶段 5 的第二次生成入口。反馈放在用户问题之后，模型先看到
     * 原始 schema 和问题，再看到「上一版哪里不对」，更容易只修 SQL 的错误部分。
     * 约束里明确要求只返回 SQL，避免模型把分析过程当成下一次 SQL。
     */
    public String correctionPrompt(SchemaContext schema, String question,
                                   String previousSql, String feedback) {
        return userPrompt(schema, question)
                + "\n\n=== 上一次 SQL 与评估反馈 ===\n"
                + "上一条 SQL:\n"
                + (previousSql == null ? "（没有生成出 SQL）" : previousSql.strip())
                + "\n\n反馈:\n"
                + (feedback == null ? "请检查上一条 SQL 的表、字段、连接、聚合粒度和输出列。"
                : feedback.strip())
                + "\n\n请只输出修正后的 SQL，不要输出解释、markdown 或多个候选。\n";
    }
}
