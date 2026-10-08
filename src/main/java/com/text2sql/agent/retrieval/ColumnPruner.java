package com.text2sql.agent.retrieval;

import com.text2sql.agent.retrieval.glossary.Glossary;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import com.text2sql.agent.semantic.Metric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 动态列级剪枝器（Column-level Schema Pruner）。
 *
 * <p><b>解决的核心痛点：宽表冗余字段稀释大模型注意力与消耗无谓 Token</b>
 *
 * <p>在表级召回后，每张表可能包含多达数十列（如 Olist 的 orders 表有 8 列，products 表有 9 列）。
 * 大量技术审计列（如各类中间时间戳、规格长宽高尺寸、无关编码等）随 Schema 注入 Prompt 后，
 * 不仅白白耗费约 30%~50% 的 Prompt Token，更容易让大模型在写复杂多表 JOIN 或 SELECT 投影时
 * 产生注意力分散，选错等价列或漏掉核心聚合列。
 *
 * <p><b>剪枝保全规则（Safety Invariants）</b>：
 * <ol>
 *   <li><b>关联键永远保全</b>：候选表子图内部涉及的全部主键与外键列绝对保留，杜绝 JOIN 悬空；</li>
 *   <li><b>实体与枚举对齐列永远保全</b>：{@link ValueRetriever} 命中的列保全；</li>
 *   <li><b>业务指标依赖列永远保全</b>：命中的业务指标在 expression/filter 中引用的列保全；</li>
 *   <li><b>提问语义命中的列保全</b>：提问文本中直接包含列名或词典列别名（如“价格”->price，“运费”->freight_value）；</li>
 *   <li><b>下限安全兜底（Guard）</b>：若筛选后列数少于 {@code minColumns}，自动按注释及原表顺序补齐，避免破坏宽表语义。</li>
 * </ol>
 */
@Component
public class ColumnPruner {

    private static final Logger log = LoggerFactory.getLogger(ColumnPruner.class);

    private final GlossaryLoader glossaryLoader;

    public ColumnPruner(GlossaryLoader glossaryLoader) {
        this.glossaryLoader = glossaryLoader;
    }

    /**
     * 对候选表集合执行动态列级剪枝。
     *
     * @param tables           候选表原始集合
     * @param question         用户自然语言提问
     * @param targetTableNames 当前候选表名集合（全部小写或大小写不敏感比对）
     * @param foreignKeys      当前候选表范围内的外键边
     * @param valueMatches     实体检索器命中的枚举与实体对齐项（可为空）
     * @param metrics          本次命中的业务指标列表（可为空）
     * @param minColumns       单张表保留列数的安全下限
     * @return 经过列级裁剪后的表对象列表
     */
    public List<SchemaContext.Table> prune(
            List<SchemaContext.Table> tables,
            String question,
            Set<String> targetTableNames,
            List<SchemaContext.ForeignKey> foreignKeys,
            List<ValueRetriever.ValueMatch> valueMatches,
            List<Metric> metrics,
            int minColumns) {

        if (tables == null || tables.isEmpty()) {
            return List.of();
        }

        Glossary glossary = glossaryLoader != null ? glossaryLoader.get() : null;
        String qLower = question != null ? question.toLowerCase(Locale.ROOT) : "";
        Set<String> normalizedTargets = new HashSet<>();
        if (targetTableNames != null) {
            targetTableNames.forEach(t -> normalizedTargets.add(t.toLowerCase(Locale.ROOT)));
        }

        List<SchemaContext.Table> prunedList = new ArrayList<>();
        int originalTotalCols = 0;
        int prunedTotalCols = 0;

        for (SchemaContext.Table table : tables) {
            originalTotalCols += table.columns().size();

            // 若表自身字段总数本身就不超过下限，直接全量保留
            if (table.columns().size() <= minColumns) {
                prunedList.add(table);
                prunedTotalCols += table.columns().size();
                continue;
            }

            Set<String> keepCols = new LinkedHashSet<>();
            String tableNameLower = table.name().toLowerCase(Locale.ROOT);

            // 1. 保全主键与外键关联列（同时包含数据库物理外键与词典约定关联）
            for (SchemaContext.Column col : table.columns()) {
                String colLower = col.name().toLowerCase(Locale.ROOT);
                // 主键启发式
                if (isPrimaryKeyCandidate(tableNameLower, colLower)) {
                    keepCols.add(colLower);
                }
            }
            if (foreignKeys != null) {
                for (SchemaContext.ForeignKey fk : foreignKeys) {
                    if (fk.fromTable().equalsIgnoreCase(table.name()) && normalizedTargets.contains(fk.toTable().toLowerCase(Locale.ROOT))) {
                        keepCols.add(fk.fromColumn().toLowerCase(Locale.ROOT));
                    }
                    if (fk.toTable().equalsIgnoreCase(table.name()) && normalizedTargets.contains(fk.fromTable().toLowerCase(Locale.ROOT))) {
                        keepCols.add(fk.toColumn().toLowerCase(Locale.ROOT));
                    }
                }
            }
            if (glossary != null && glossary.relations() != null) {
                for (Glossary.Relation rel : glossary.relations()) {
                    if (rel.from() != null && rel.to() != null) {
                        String[] fromParts = rel.from().split("\\.");
                        String[] toParts = rel.to().split("\\.");
                        if (fromParts.length == 2 && toParts.length == 2) {
                            String fromTable = fromParts[0].trim();
                            String fromCol = fromParts[1].trim();
                            String toTable = toParts[0].trim();
                            String toCol = toParts[1].trim();

                            if (fromTable.equalsIgnoreCase(table.name()) && normalizedTargets.contains(toTable.toLowerCase(Locale.ROOT))) {
                                keepCols.add(fromCol.toLowerCase(Locale.ROOT));
                            }
                            if (toTable.equalsIgnoreCase(table.name()) && normalizedTargets.contains(fromTable.toLowerCase(Locale.ROOT))) {
                                keepCols.add(toCol.toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                }
            }

            // 2. 保全实体与枚举对齐涉及列
            if (valueMatches != null) {
                for (ValueRetriever.ValueMatch vm : valueMatches) {
                    if (vm.table().equalsIgnoreCase(table.name())) {
                        keepCols.add(vm.column().toLowerCase(Locale.ROOT));
                    }
                }
            }

            // 3. 保全指标引用的列
            if (metrics != null) {
                for (Metric m : metrics) {
                    if (m.tables() != null && m.tables().stream().anyMatch(t -> t.equalsIgnoreCase(table.name()))) {
                        String expr = (m.expression() + " " + (m.filter() != null ? m.filter() : "")).toLowerCase(Locale.ROOT);
                        for (SchemaContext.Column col : table.columns()) {
                            if (expr.contains(col.name().toLowerCase(Locale.ROOT))) {
                                keepCols.add(col.name().toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                }
            }

            // 4. 保全用户提问中命中的英文字段名或中文别名
            Glossary.TableEntry tableGlossary = (glossary != null && glossary.tables() != null)
                    ? glossary.tables().get(table.name()) : null;

            for (SchemaContext.Column col : table.columns()) {
                String colLower = col.name().toLowerCase(Locale.ROOT);
                // 英文列名直接出现在提问中
                if (!qLower.isBlank() && qLower.contains(colLower)) {
                    keepCols.add(colLower);
                    continue;
                }
                // 词典中文别名命中
                if (tableGlossary != null && tableGlossary.columns() != null) {
                    Glossary.ColumnEntry colEntry = tableGlossary.columns().get(col.name());
                    if (colEntry != null) {
                        if (colEntry.aliases() != null && colEntry.aliases().stream().anyMatch(a -> !a.isBlank() && question.contains(a))) {
                            keepCols.add(colLower);
                            continue;
                        }
                        if (colEntry.enumValues() != null && colEntry.enumValues().stream().anyMatch(e -> e.label() != null && question.contains(e.label()))) {
                            keepCols.add(colLower);
                        }
                    }
                }
            }

            // 4.5 时间/时序语义感知保全：当用户问题包含时间、周期或时长语义时，保全表内的时间戳与日期列
            if (isTemporalQuestion(question)) {
                for (SchemaContext.Column col : table.columns()) {
                    String colLower = col.name().toLowerCase(Locale.ROOT);
                    String typeLower = col.type() != null ? col.type().toLowerCase(Locale.ROOT) : "";
                    if (typeLower.contains("time") || typeLower.contains("date")
                            || colLower.endsWith("_at") || colLower.endsWith("_date") || colLower.endsWith("_time")
                            || colLower.contains("timestamp")) {
                        keepCols.add(colLower);
                    }
                }
            }

            // 5. 下限兜底保护：若不足 minColumns，优先按“有注释”与“原顺序”补充字段
            if (keepCols.size() < minColumns) {
                for (SchemaContext.Column col : table.columns()) {
                    String colLower = col.name().toLowerCase(Locale.ROOT);
                    if (col.comment() != null && !col.comment().isBlank()) {
                        keepCols.add(colLower);
                        if (keepCols.size() >= minColumns) break;
                    }
                }
            }
            if (keepCols.size() < minColumns) {
                for (SchemaContext.Column col : table.columns()) {
                    keepCols.add(col.name().toLowerCase(Locale.ROOT));
                    if (keepCols.size() >= minColumns) break;
                }
            }

            // 按照原表列顺序过滤出最终保留的列
            List<SchemaContext.Column> finalCols = table.columns().stream()
                    .filter(c -> keepCols.contains(c.name().toLowerCase(Locale.ROOT)))
                    .toList();

            prunedList.add(new SchemaContext.Table(table.name(), table.comment(), finalCols));
            prunedTotalCols += finalCols.size();
        }

        log.debug("动态列级剪枝完成：总列数 {} -> {}（压缩率 {:.1f}%）",
                originalTotalCols, prunedTotalCols,
                originalTotalCols > 0 ? (100.0 * (originalTotalCols - prunedTotalCols) / originalTotalCols) : 0.0);

        return prunedList;
    }

    private boolean isPrimaryKeyCandidate(String tableNameLower, String colNameLower) {
        if ("id".equals(colNameLower)) {
            return true;
        }
        if (colNameLower.equals(tableNameLower + "_id")) {
            return true;
        }
        // 比如 orders -> order_id, customers -> customer_id, products -> product_id
        if (tableNameLower.endsWith("s") && colNameLower.equals(tableNameLower.substring(0, tableNameLower.length() - 1) + "_id")) {
            return true;
        }
        if ("order_items".equals(tableNameLower) && ("order_id".equals(colNameLower) || "order_item_id".equals(colNameLower))) {
            return true;
        }
        return false;
    }

    private static final java.util.regex.Pattern TEMPORAL_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(月|周|日|天|年|小时|时长|时间|趋势|环比|同比|期间|新增|最近|多久|何时|日期|time|date|hour|day|month|year)");

    private boolean isTemporalQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return TEMPORAL_PATTERN.matcher(question).find();
    }
}

