package com.text2sql.agent.retrieval;

import java.util.List;

/**
 * 把结构化 schema 渲染成给模型看的文本。
 *
 * <p>格式选择：用「表名 / 列清单」的紧凑写法，而不是原样贴 CREATE TABLE。
 * 原因有两个：一是 CREATE TABLE 里的类型细节、约束名对生成 SQL 没用，
 * 白占 token；二是列注释（藏着枚举值口径）在 CREATE TABLE 里不显眼，
 * 单独提出来才容易被模型利用。
 *
 * <p>被否掉的方案：直接 dump 建表语句。它更省事，但会把 8KB 里一大半
 * 花在 {@code character varying(255) NOT NULL} 这种信息量极低的字符上。
 *
 * <p>为什么单独抽成一个类：阶段 2 的检索层要渲染「召回表的子集」，
 * 阶段 1 的全量实现要渲染「全部表」。渲染逻辑必须只有一份，
 * 否则「检索版和 baseline 的 prompt 差异」里会混入格式差异，
 * 消融实验的数字就不再干净。
 *
 * <p><b>阶段 3 为什么保留两个 render 方法</b>
 *
     * <p>检索路径升级成「列级 join 关系图」（{@link #renderWithJoins}），
     * 而 baseline 路径**刻意不动**：47% 那个数字是在「只有外键、
 * 表头叫 FOREIGN KEYS」的 prompt 下测出来的。如果连 baseline 一起改，
 * 历史数字作废、阶段 2 与阶段 3 的对照也要全部重跑——而 baseline
 * 本来就不该被优化影响。
 *
 * <p>两个方法共用 {@link #renderTables}，差异只在边那一段，所以
 * 「渲染逻辑只有一份」这条原则没有被破坏：表格部分的格式不可能分叉。
 */
public final class SchemaDdlRenderer {

    private SchemaDdlRenderer() {
    }

    /**
     * 渲染 schema 子集。
     *
     * <p><b>阶段 3 起边不再只有外键</b>：{@code edges} 来自
     * {@link JoinGraph#edgesAmong}，同时包含数据库外键与词典约定关联。
     * 段标题也从 {@code FOREIGN KEYS} 改成 {@code JOINS}——因为里面
     * 已经不全是外键了，继续叫 FOREIGN KEYS 会误导模型把每条边都当成
     * 数据库强约束。用 {@code [FK]} 标记区分：带标记的是外键，
     * 不带的是人工核对的关联，可信度略低但同样是有效的 join 路径。
     *
     * <p>这一改动直接修的是 T4-015 那类错误：模型看到
     * {@code customers.customer_state -> regions.region_code} 就不会再
     * 猜成 {@code region_name}。
     */
    public static String renderWithJoins(List<SchemaContext.Table> tables, List<JoinGraph.Edge> edges) {
        return renderWithJoins(tables, edges, null);
    }

    /**
     * 渲染 schema 子集，并附上**规划好的连接顺序**。
     *
     * <p>两个 join 段各回答一个问题，缺一不可：
     *
     * <ul>
     *   <li>{@code JOINS}（全部边）：这张子图里**存在**哪些关联。给全，
     *       因为模型可能需要用到非主干路径上的边。</li>
     *   <li>{@code JOIN PLAN}（生成树）：**推荐**按什么顺序连。给一条，
     *       因为多条路径都连通时，边列表不会告诉模型哪条更可信。</li>
     * </ul>
     *
     * <p>被否掉的方案：只给 JOIN PLAN。那样模型看不到备选路径，
     * 万一规划选错了（比如词典关联写错），它连纠正的机会都没有。
     * 两个段一起给，是「给建议但不剥夺判断权」。
     *
     * @param plan 连接树；为 null 或空时不输出 JOIN PLAN 段
     */
    public static String renderWithJoins(List<SchemaContext.Table> tables, List<JoinGraph.Edge> edges,
                                         JoinPathPlanner.Plan plan) {
        StringBuilder sb = new StringBuilder();
        renderTables(sb, tables);
        if (!edges.isEmpty()) {
            sb.append("JOINS\n");
            for (JoinGraph.Edge edge : edges) {
                sb.append("  ").append(edge.render()).append('\n');
            }
        }
        String planText = JoinPathPlanner.render(plan);
        if (!planText.isEmpty()) {
            sb.append('\n').append("JOIN PLAN（推荐的连接顺序）\n").append(planText).append('\n');
        }
        return sb.toString().strip();
    }

    /**
     * 阶段 1 形态的渲染：边只有数据库外键，表头是 {@code FOREIGN KEYS}。
     *
     * <p><b>保留它就是为了让 baseline 保持可复现。</b>baseline 的职责是
     * 「不做任何优化时能拿到多少」，所以它不该跟着阶段 3 一起变。
     * 阶段 3 的改进只作用在检索路径上，对照关系因此是干净的：
     *
     * <pre>
     *   baseline(47%)    ── 不变
     *   阶段2 检索(52%)   ── 只加表选择
     *   阶段3 检索(待测)   ── 表选择 + 列级 join 关系图
     * </pre>
     */
    public static String render(List<SchemaContext.Table> tables, List<SchemaContext.ForeignKey> fks) {
        StringBuilder sb = new StringBuilder();
        renderTables(sb, tables);
        if (!fks.isEmpty()) {
            sb.append("FOREIGN KEYS\n");
            for (SchemaContext.ForeignKey fk : fks) {
                sb.append("  ").append(fk.fromTable()).append('.').append(fk.fromColumn())
                        .append(" -> ").append(fk.toTable()).append('.').append(fk.toColumn())
                        .append('\n');
            }
        }
        return sb.toString().strip();
    }

    /** 表与列的渲染。两个 render 方法共用，保证表格格式不可能分叉。 */
    private static void renderTables(StringBuilder sb, List<SchemaContext.Table> tables) {
        for (SchemaContext.Table table : tables) {
            sb.append("TABLE ").append(table.name());
            if (table.comment() != null && !table.comment().isBlank()) {
                sb.append("  -- ").append(table.comment().strip());
            }
            sb.append('\n');
            for (SchemaContext.Column col : table.columns()) {
                sb.append("  ").append(col.name()).append(' ').append(col.type());
                if (col.comment() != null && !col.comment().isBlank()) {
                    sb.append("  -- ").append(col.comment().strip());
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
    }
}
