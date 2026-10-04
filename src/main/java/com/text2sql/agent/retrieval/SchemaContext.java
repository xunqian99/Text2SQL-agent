package com.text2sql.agent.retrieval;

import java.util.List;
import java.util.Set;

/**
 * 一次请求要用到的 schema 信息，是「检索层」唯一的输出类型。
 *
 * <p>为什么要有这个中间类型，而不是让检索层直接返回拼好的 prompt 字符串：
 * 阶段 2 引入混合检索后，需要单独衡量「表召回率」——也就是「该用的表有没有被召回」。
 * 如果检索层直接吐字符串，就没法对召回结果做统计，只能靠人肉看日志。
 * 所以检索层的职责边界是「选出相关信息」，怎么措辞交给生成层。
 *
 * <p>阶段 1 的实现是 {@link FullSchemaProvider}：把 37 张表全塞进去，故意不做筛选。
 */
public record SchemaContext(
        List<Table> tables,
        List<ForeignKey> foreignKeys,
        String dataProfile,   // 数据画像（可选，可能为空串）
        String ddlText,
        String metricsText) {  // 命中的业务指标定义（阶段 4，可选，可能为空串）

    /**
     * 兼容旧签名的构造器：不注入业务指标。
     *
     * <p><b>为什么要留这个重载</b>：加上 {@code metricsText} 之后，
     * 所有 {@code new SchemaContext(tables, fks, profile, ddl)} 的调用点都会编译失败。
     * 那些调用点里绝大多数（测试、baseline 路径）本来就不需要指标——
     * 逐个改成传空串是纯粹的机械劳动，还会让 diff 里混进大量噪声。
     *
     * <p>给一个 4 参数重载，语义是「这份上下文不含指标定义」，
     * 既保住了编译，也让「谁真的用了指标」在 diff 里一眼可见。
     */
    public SchemaContext(List<Table> tables, List<ForeignKey> foreignKeys,
                         String dataProfile, String ddlText) {
        this(tables, foreignKeys, dataProfile, ddlText, "");
    }

    /** 是否注入了业务指标定义。 */
    public boolean hasMetrics() {
        return metricsText != null && !metricsText.isBlank();
    }

    /** 本次上下文包含的表名，供评估层计算表召回率。 */
    public List<String> tableNames() {
        return tables.stream().map(Table::name).toList();
    }

    /** 一张表。comment 是表级注释，多数表为空——这本身就是需要模型面对的噪声。 */
    public record Table(String name, String comment, List<Column> columns) {
    }

    /** 一列。comment 里藏着枚举值口径（如 "会员状态：1=正常 2=冻结"），是语义层的雏形。 */
    public record Column(String name, String type, boolean nullable, String comment) {
    }

    /**
     * 一条外键。阶段 3 会用这些边构建表关系图并求最短路径。
     *
     * <p>阶段 1 只把它当作文本提示塞进 prompt，让模型自己决定怎么 join。
     * 这正是阶段 3 要改掉的地方：模型选错 join 时 SQL 照样能执行，错误是静默的。
     */
    public record ForeignKey(String fromTable, String fromColumn, String toTable, String toColumn) {
    }

    public int columnCount() {
        return tables.stream().mapToInt(t -> t.columns().size()).sum();
    }

    /**
     * 从全量 schema 里切出子集，供检索层使用。
     *
     * <p><b>外键为什么要跟着过滤</b>：如果只留表不留边，模型看到
     * {@code order_items} 和 {@code products} 却不知道它们怎么关联，
     * 就会去猜 join 条件——而猜错的 join 照样能执行，错误是静默的。
     * 外键本身占不了多少 token，留全反而更安全。
     *
     * <p><b>只保留两端都在子集里的边</b>，而不是把全库外键都塞进来：
     * 后者会引入「表都没召回，却告诉模型它怎么 join」的矛盾信息。
     *
     * <p>被否掉的方案：在 provider 里手工拼一个新的 SchemaContext。
     * 那样每加一个 provider 就要重写一遍过滤逻辑，且容易漏掉某一项
     * （比如忘了重新渲染 ddlText，导致 prompt 里还是全量表）。
     * 把「切子集」这个动作收敛到 record 自己的方法上，语义只有一份。
     */
    public SchemaContext subset(Set<String> tableNames, String ddlText) {
        List<Table> kept = tables.stream()
                .filter(t -> tableNames.contains(t.name()))
                .toList();
        List<ForeignKey> keptEdges = foreignKeys.stream()
                .filter(fk -> tableNames.contains(fk.fromTable()) && tableNames.contains(fk.toTable()))
                .toList();
        return new SchemaContext(kept, keptEdges, dataProfile, ddlText, metricsText);
    }

    /**
     * 附加业务指标定义，返回新实例。
     *
     * <p><b>为什么用「附加」而不是在构造时就传入</b>：指标能不能注入取决于
     * **最终选中了哪些表**（指标依赖的表必须都在上下文里，否则模型会照抄一个
     * 引用不存在表的表达式）。而选中哪些表是检索层的结果，构造 SchemaContext
     * 时还不知道。所以流程必须是「先切子集 → 再按可用表筛指标 → 附加」。
     *
     * <p>record 不可变，所以这里返回新对象而不是就地修改。
     */
    public SchemaContext withMetrics(String metricsText) {
        return new SchemaContext(tables, foreignKeys, dataProfile, ddlText, metricsText);
    }
}
