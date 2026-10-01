package com.text2sql.agent.evaluation;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 表召回率的计算与「期望表」的提取。
 *
 * <p><b>期望表从 gold_sql 解析出来，而不是人工标注</b>
 *
 * <p>被否掉的方案：在 200 条评估项里手写 {@code expected_tables: [orders, customers]}。
 * 它有三个问题。一是标注成本：200 条 × 平均 2 张表，且每次修正 gold_sql
 * 都要同步改标注，很容易漏。二是必然分叉：gold_sql 改了而标注没改，
 * 召回率就会朝着错误的方向漂移，而且没人会发现。三是它把一个可以
 * 机械推导的东西变成了人工资产。
 *
 * <p>从 gold_sql 解析则天然同步——标准答案用到哪些表，就是期望召回哪些表。
 * 代价是「gold_sql 里的表 ≠ 业务上必须的表」这一细微差异：有些 gold_sql
 * 可能用了 CTE 或子查询绕路，用到了非必需的表。实测这种情况在本评估集里
 * 很少，且它偏向「期望更宽松」，不会虚高召回率。
 *
 * <p>解析用 JSqlParser 的 {@code TablesNamesFinder}。实测它**会自动排除
 * CTE 别名**（{@code WITH all_orders AS (...)} 里的 {@code all_orders}
 * 不会出现在结果里），这一点很关键——评估集里大量使用 CTE，
 * 如果不排除，期望表里会混进一堆虚构的表名，召回率直接失去意义。
 */
public final class TableRecall {

    private TableRecall() {
    }

    /**
     * 从 SQL 里解析出它引用的真实表名（小写）。
     *
     * <p>解析失败时返回空集合而不是抛异常：评估集的 gold_sql 理论上都合法
     * （dry-run 会先验证），但一条解析失败不应该让整轮评估崩掉。
     * 返回空集合的语义是「无法判定」，调用方会把它排除在召回率统计之外，
     * 而不是当成「召回了 0 张表」——后者会冤枉检索层。
     */
    public static Set<String> expectedTables(String sql) {
        if (sql == null || sql.isBlank()) {
            return Set.of();
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            Set<String> tables = new LinkedHashSet<>();
            for (String table : new TablesNamesFinder<Void>().getTableList(statement)) {
                tables.add(table.toLowerCase(Locale.ROOT));
            }
            return tables;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * 单条样本的召回情况。
     *
     * @param expected 期望表（gold_sql 用到的）
     * @param retrieved 实际交给模型的表
     * @param hit 期望表里被召回的数量
     */
    public record Outcome(Set<String> expected, Set<String> retrieved, int hit) {

        /** 全部期望表都被召回。 */
        public boolean fullRecall() {
            return !expected.isEmpty() && hit == expected.size();
        }

        /** 一条都没召回。这是最值得盯的失败类型：检索完全没找对方向。 */
        public boolean missed() {
            return !expected.isEmpty() && hit == 0;
        }

        public Set<String> missing() {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(retrieved);
            return missing;
        }

        /**
         * 精确率：召回了的表里有多少是真正需要的。
         *
         * <p>它和召回率是一对张力。只优化召回率，把 37 张表全给模型就必然
         * 100%，但那等于退回 baseline。所以两个指标必须一起看：
         * 召回率不掉的前提下，精确率上升才说明检索真的在筛选。
         */
        public double precision() {
            return retrieved.isEmpty() ? 0 : (double) hit / retrieved.size();
        }
    }

    public static Outcome evaluate(String goldSql, List<String> retrievedTables) {
        Set<String> expected = expectedTables(goldSql);
        Set<String> retrieved = new LinkedHashSet<>();
        for (String table : retrievedTables) {
            retrieved.add(table.toLowerCase(Locale.ROOT));
        }
        int hit = 0;
        for (String table : expected) {
            if (retrieved.contains(table)) {
                hit++;
            }
        }
        return new Outcome(expected, retrieved, hit);
    }
}
