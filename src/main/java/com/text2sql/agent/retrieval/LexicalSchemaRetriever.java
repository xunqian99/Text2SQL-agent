package com.text2sql.agent.retrieval;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.retrieval.glossary.Glossary;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 词法检索：靠中文语义词典把问题里的词映射到表、列、枚举值。
 *
 * <p><b>为什么不是 PostgreSQL 的 tsvector（这一条推翻过 ROADMAP 原计划）</b>
 *
 * <p>实测证据：{@code SELECT to_tsvector('english','统计每个州的订单数量')} 的结果是
 * {@code '统计每个州的订单数量':1}——整串被当成一个 token。换 {@code 'simple'} 一样。
 * 也就是说 PG 的内置分词器**不切分中文**，只在空格分隔时才有效
 * （{@code '订单 数量 州'} 才能切成三个 token）。
 * 中文自然语言问题里不会有空格，所以 tsvector 这条路对中文是死的。
 *
 * <p>{@code pg_trgm} 同样不行：{@code show_trgm('订单数量')} 返回空数组，
 * {@code similarity('统计每个州的订单数量','订单')} 返回 0——三元组基于字符，
 * 而中文的「词」不等于「字的三元组」。
 *
 * <p>所以分词必须放在应用层。但这里**没有**引入 jieba / HanLP 这类通用分词器，
 * 理由是：通用分词器能把「复购率」切成「复购」「率」，但它仍然不知道
 * 「复购」对应哪张表——真正的难点不是切词，是**领域映射**。
 * 直接做「词典驱动的最大匹配」把两件事合成一步，且词典可审阅、可增删。
 *
 * <p><b>为什么用最大匹配（长词优先）而不是简单包含判断</b>
 *
 * <p>「订单明细」和「订单」是两个不同的词，指向不同的表。如果对每个别名
 * 独立做 {@code question.contains(alias)}，问「订单明细里有多少商品」时
 * 会同时命中 {@code order_items}（来自「订单明细」）和 {@code orders}
 * （来自「订单」），而 {@code orders} 是被「订单明细」包含的误命中。
 * 从左到右取最长匹配能消掉这类噪声。
 *
 * <p>被否掉的方案：对每个别名做 {@code contains} 然后按分数排序。
 * 它在「订单明细」这类词上必然误召回，而误召回会挤占 Top-K 名额，
 * 直接拉低精确率。
 */
@Component
public class LexicalSchemaRetriever {

    private static final Logger log = LoggerFactory.getLogger(LexicalSchemaRetriever.class);

    private final GlossaryLoader glossaryLoader;
    private final SchemaCatalog catalog;
    private final AgentProperties properties;

    /** 词典展开后的匹配表，加载一次后复用。键是中文/英文说法，值是它的归属。 */
    private volatile List<MatchTerm> terms;

    /**
     * 表关系图，加载一次后复用。
     *
     * <p>阶段 3 起，关系图的定义收敛到 {@link JoinGraph} 一处——检索打分和
     * prompt 渲染必须看到同一张图，否则就会出现「检索器知道怎么 join、
     * 模型不知道」的裂缝。那正是 T4-015 猜错列名的根因。
     *
     * <p>为什么缓存在这里而不是做成 Spring Bean：它由 schema 与词典决定，
     * 两者在一次运行内都不变，缓存一次即可；而且它是纯值对象，
     * 不做成 Bean 可以让单元测试直接构造，不必起容器。
     */
    private volatile JoinGraph joinGraph;

    public LexicalSchemaRetriever(GlossaryLoader glossaryLoader, SchemaCatalog catalog,
                                  AgentProperties properties) {
        this.glossaryLoader = glossaryLoader;
        this.catalog = catalog;
        this.properties = properties;
    }

    public RetrievalResult retrieve(String question) {
        AgentProperties.Retrieval config = properties.getRetrieval();
        Map<String, SchemaContext.Table> tablesByName = catalog.tablesByName();

        List<MatchTerm> allTerms = terms();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, List<String>> evidence = new LinkedHashMap<>();
        Set<String> directHits = new LinkedHashSet<>();

        // ---- 第一步：最大匹配扫描，收集直接命中 ----
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        int index = 0;
        while (index < text.length()) {
            MatchTerm matched = longestMatchAt(text, index, allTerms);
            if (matched == null) {
                index++;
                continue;
            }
            // 长度只有 1 的别名（如「州」「券」「仓」）误命中率明显更高：
            // 「广州」里含「州」、「优惠」里含「券」都可能被误伤。
            // 不禁止它们（那会丢掉「各州的订单量」这种问法的召回），
            // 而是把权重打折——召回优先，但让长词在排序上赢。
            double weight = matched.weight() * (matched.term().length() == 1
                    ? config.getSingleCharWeightFactor()
                    : 1.0);
            for (String table : matched.tables()) {
                addScore(scores, table, weight);
                addEvidence(evidence, table, "%s「%s」".formatted(matched.kind().label(), matched.term()));
                directHits.add(table);
            }
            index += matched.term().length();
        }

        // ---- 第二步：英文标识符直接出现（用户偶尔会直接写表名/列名） ----
        for (Map.Entry<String, SchemaContext.Table> entry : tablesByName.entrySet()) {
            if (containsIdentifier(text, entry.getKey())) {
                addScore(scores, entry.getValue().name(), config.getIdentifierHitWeight());
                addEvidence(evidence, entry.getValue().name(), "标识符「%s」".formatted(entry.getKey()));
                directHits.add(entry.getValue().name());
            }
        }

        // ---- 第三步：一个词都没命中时回退全量 ----
        // 这是刻意的保守选择：检索器宁可多给上下文，也不能因为词典没收录
        // 某个说法就把必要的表筛掉——筛掉之后模型必然生成错误 SQL，
        // 而多给几张表只是浪费 token。回退率本身也是个要监控的指标：
        // 回退率高说明词典覆盖不足，需要补词。
        if (scores.isEmpty()) {
            List<String> all = tablesByName.values().stream().map(SchemaContext.Table::name).toList();
            return new RetrievalResult(all, Map.of(), Map.of(), Set.of(), true);
        }

        // ---- 第四步：关系扩展 ----
        // 命中 order_items 时，模型还需要知道它怎么连到 orders / products。
        // 但扩展必须克制：全图展开会让「只问一张表」的问题也带进十几张表，
        // 那就退化成 baseline 了。
        Set<String> expanded = new LinkedHashSet<>();
        Map<String, Map<String, Double>> adjacency = joinGraph().tableAdjacency();
        if (config.getRelationHops() > 0) {
            List<String> seeds = directHits.stream()
                    .sorted(Comparator.comparingDouble((String t) -> scores.getOrDefault(t, 0.0)).reversed())
                    .limit(config.getExpansionSeedLimit())
                    .toList();
            double decay = config.getRelationDecay();
            Set<String> frontier = new LinkedHashSet<>(seeds);
            for (int hop = 0; hop < config.getRelationHops(); hop++) {
                Set<String> next = new LinkedHashSet<>();
                for (String from : frontier) {
                    // 传播强度必须带上**源表自己的分数**。
                    //
                    // 被否掉的方案：所有扩展出来的表拿同一个固定分
                    // （decay × 边强度）。那样「高置信度表的邻居」和
                    // 「勉强命中表的邻居」完全等价，排序退化成邻接表的
                    // 插入顺序。实测后果：问「每个月的商品销售额」时
                    // order_items（置信度 2.0，携带金额）和 products
                    // （置信度 2.5，只是商品维度）的邻居混在一起，
                    // orders 被 product_brand_map 这类表挤掉。
                    //
                    // 乘上源分数后，扩展分数的语义变成「这条路径有多可信」，
                    // 与直接命中分数同量纲、可直接比较。
                    double base = scores.getOrDefault(from, 0.0);
                    for (Map.Entry<String, Double> edge : adjacency.getOrDefault(from, Map.of()).entrySet()) {
                        String to = edge.getKey();
                        if (directHits.contains(to)) {
                            continue; // 已直接命中，不覆盖它自己的分数
                        }
                        double delta = base * decay * edge.getValue();
                        // 同一张表可能有多条路径可达（比如 orders 既能从
                        // order_items 走外键到，也能从 support_tickets 走
                        // 约定关联到）。取**最大**分数而不是累加：累加会让
                        // 「邻居多的表」靠数量堆分数，而我们要的是「最强的
                        // 那条路径有多可信」。
                        if (delta <= scores.getOrDefault(to, 0.0)) {
                            continue;
                        }
                        scores.put(to, delta);
                        evidence.put(to, new ArrayList<>(List.of("经「%s」关联".formatted(from))));
                        expanded.add(to);
                        next.add(to);
                    }
                }
                decay *= config.getRelationDecay();
                frontier = next;
            }
        }

        // ---- 第五步：取 Top-K ----
        // 排序必须是**全序**，不能只按分数。
        //
        // 被否掉的写法：只写 comparingByValue().reversed()。Stream.sorted 是稳定
        // 排序，同分时保留的是 scores 这个 LinkedHashMap 的插入顺序——而插入顺序
        // 由词典的书写顺序和邻接表的构造顺序决定。后果是「往词典里加一条跟本题
        // 无关的关联边」会改变同分表的先后，进而改变 Top-K 的入选名单。
        // 实测踩到过：给类目翻译表补了一条 join 边，T4-016 的 customers 就被挤出去了。
        // 一个检索系统的输出不应该对无关编辑敏感，所以这里补上确定性的次级排序键。
        //
        // 次级键的语义顺序：分数 -> 是否直接命中 -> 表名。
        // 「直接命中优先于关系扩展」是有依据的：直接命中是用户话里真的说了，
        // 扩展只是图上够得着，前者可信度更高。最后按表名兜底，纯粹为了可复现。
        Comparator<Map.Entry<String, Double>> rankOrder =
                Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(e -> !directHits.contains(e.getKey()))
                        .thenComparing(Map.Entry::getKey);
        List<String> ranked = new ArrayList<>(scores.entrySet().stream()
                .sorted(rankOrder)
                .map(Map.Entry::getKey)
                .limit(config.getTopK())
                .toList());

        // ---- 第六步：连通性修复 ----
        // relation-hops=0 是「完全不用关系图」的消融开关，连通性修复依赖
        // 同一张图，所以必须一起关掉，否则消融实验量到的不是同一个变量。
        if (config.isBridgeRepairEnabled() && config.getRelationHops() > 0 && ranked.size() > 1) {
            ranked = repairConnectivity(ranked, adjacency, config, scores, evidence, expanded);
        }

        return new RetrievalResult(ranked, scores, evidence, expanded, false);
    }

    /**
     * 连通性修复：把 Top-K 拼成一个**能 join 起来**的子图。
     *
     * <p><b>为什么需要这一步</b>
     *
     * <p>检索的评分只回答「这个词像哪张表」，它不回答「这些表能不能连起来」。
     * 而 SQL 要跑通，需要的表必须构成连通子图——中间少一张桥接表，
     * 整条 join 路径就断了。
     *
     * <p>实测的典型受害者是 {@code customers}：它是
     * {@code orders ↔ members ↔ regions ↔ shipping_addresses} 的中枢，
     * 但问「每个大区的商品销售额」时，用户根本不会说「客户」两个字。
     * 纯词法打分永远够不着它，于是 {@code regions} 和 {@code orders}
     * 各自被召回、彼此却连不上，模型只能瞎猜一个 join 条件。
     *
     * <p>注意这里**不是**「多召回几张表」那种含糊的兜底。它只补
     * **能消除不连通**的那几张表，补几张、补谁，由关系图上的最短路径决定。
     * 已经连通的问题一张都不补——这正是它和「把 topK 调大」的本质区别：
     * 后者是无差别加表，会让精确率一起掉。
     *
     * <p>被否掉的替代方案一：把 topK 从 8 调到 12。实测这种做法会让
     * 每题的上下文多出一半无关表，精确率掉、token 涨，而桥接表能不能
     * 挤进前 12 依然是碰运气。
     *
     * <p>被否掉的替代方案二：把桥接关系直接写进词典，让「大区」同时
     * 命中 {@code customers}。这等于把「哪张表是中枢」硬编码成词条，
     * 换一个数据集就全部失效；而且它解释不了「为什么命中客户表」，
     * 面试时说不清。连通性是图的性质，就该用图算法算出来。
     *
     * <p>被否掉的替代方案三：无上限地补到全连通。多组件时可能需要
     * 引入多张桥接表，子图会迅速膨胀回全量。所以用
     * {@code bridgeRepairMaxTables} 封顶，宁可保留不连通（模型会报错，
     * 错误可见），也不要悄悄把上下文撑爆。
     */
    private List<String> repairConnectivity(List<String> ranked,
                                            Map<String, Map<String, Double>> adjacency,
                                            AgentProperties.Retrieval config,
                                            Map<String, Double> scores,
                                            Map<String, List<String>> evidence,
                                            Set<String> expanded) {
        Set<String> selected = new LinkedHashSet<>(ranked);
        int budget = config.getBridgeRepairMaxTables();
        while (budget > 0) {
            List<String> bridge = shortestBridge(selected, adjacency);
            if (bridge.isEmpty()) {
                break;
            }
            // 路径必须整条补进来才有意义——只补一半仍然不连通，
            // 却已经吃掉了预算。装不下就整条放弃，保留原状。
            if (bridge.size() > budget) {
                log.debug("连通性修复：最短桥需要 {} 张表，超出剩余预算 {}，跳过",
                        bridge.size(), budget);
                break;
            }
            budget -= bridge.size();
            for (String table : bridge) {
                selected.add(table);
                expanded.add(table);
                // 桥接表是「为了连通」进来的，不是词面命中，所以给一个
                // 低于任何直接命中的分数：它排在队尾，但不至于消失
                // （scores 要用于报告，缺了它就没法解释这张表从哪来）。
                scores.putIfAbsent(table, 0.0);
                evidence.put(table, new ArrayList<>(List.of("连通性修复：补桥接表")));
                log.debug("连通性修复：补入桥接表 {}", table);
            }
        }
        return new ArrayList<>(selected);
    }

    /**
     * 在关系图上找一条最短路径，把 {@code selected} 中两个不同的连通分量接起来。
     *
     * @return 路径上的**中间表**（不含已选中的两端）；已经连通时返回空列表
     */
    private List<String> shortestBridge(Set<String> selected, Map<String, Map<String, Double>> adjacency) {
        Map<String, Integer> component = new LinkedHashMap<>();
        int componentCount = 0;
        for (String table : selected) {
            if (component.containsKey(table)) {
                continue;
            }
            // 只在「已选中的表」内部做 BFS，这样才能看出子图是不是断的。
            Deque<String> queue = new ArrayDeque<>();
            queue.add(table);
            component.put(table, componentCount);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                for (String neighbor : adjacency.getOrDefault(current, Map.of()).keySet()) {
                    if (selected.contains(neighbor) && !component.containsKey(neighbor)) {
                        component.put(neighbor, componentCount);
                        queue.add(neighbor);
                    }
                }
            }
            componentCount++;
        }
        if (componentCount <= 1) {
            return List.of();
        }

        // 从 0 号分量出发做多源 BFS，第一次碰到「属于别的分量」的已选表
        // 就是最短的那条桥。多源（而不是枚举点对）保证拿到的就是全局最短。
        Deque<String> frontier = new ArrayDeque<>();
        Map<String, String> parent = new LinkedHashMap<>();
        for (String table : selected) {
            if (component.get(table) == 0) {
                frontier.add(table);
                parent.put(table, null);
            }
        }
        String target = null;
        while (!frontier.isEmpty() && target == null) {
            String current = frontier.poll();
            for (String neighbor : adjacency.getOrDefault(current, Map.of()).keySet()) {
                if (parent.containsKey(neighbor)) {
                    continue;
                }
                parent.put(neighbor, current);
                if (selected.contains(neighbor) && component.get(neighbor) != 0) {
                    target = neighbor;
                    break;
                }
                frontier.add(neighbor);
            }
        }
        if (target == null) {
            return List.of(); // 图里根本没有能接上的路径，保持原样
        }

        LinkedList<String> path = new LinkedList<>();
        for (String node = parent.get(target); node != null; node = parent.get(node)) {
            if (!selected.contains(node)) {
                path.addFirst(node);
            }
        }
        return path;
    }

    /** 在 {@code text} 的 {@code start} 位置找最长的词典匹配。 */
    private MatchTerm longestMatchAt(String text, int start, List<MatchTerm> allTerms) {
        MatchTerm best = null;
        for (MatchTerm term : allTerms) {
            if (term.term().length() > text.length() - start) {
                continue;
            }
            if (!text.startsWith(term.term(), start)) {
                continue;
            }
            if (best == null || term.term().length() > best.term().length()) {
                best = term;
            }
        }
        return best;
    }

    /**
     * 把词典展开成「可匹配词 -> 归属」的列表，按词长降序。
     *
     * <p>排序是为了让最大匹配能提前命中长词；同时也让日志更可读
     * （长词在前，一眼看出哪些领域词被收录了）。
     *
     * <p>同一个词可能同时属于多个归属（比如「订单」既在 orders 表别名里，
     * 也可能出现在某列别名里）。这里做**归并**而不是保留两条：
     * 保留两条会让同一位置被匹配两次、分数翻倍，权重就不再可控。
     */
    private List<MatchTerm> terms() {
        List<MatchTerm> local = terms;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (terms != null) {
                return terms;
            }
            terms = buildTerms(glossaryLoader.get());
            return terms;
        }
    }

    private List<MatchTerm> buildTerms(Glossary glossary) {
        // 同一个词可能对应多个归属，先收集再归并。
        Map<String, List<MatchTerm>> byTerm = new LinkedHashMap<>();

        glossary.tables().forEach((tableName, entry) -> {
            for (String alias : entry.aliases()) {
                addTerm(byTerm, alias, new MatchTerm(alias.toLowerCase(Locale.ROOT),
                        MatchKind.TABLE_ALIAS, 1.0, List.of(tableName)));
            }
            entry.columns().forEach((columnName, columnEntry) -> {
                for (String alias : columnEntry.aliases()) {
                    addTerm(byTerm, alias, new MatchTerm(alias.toLowerCase(Locale.ROOT),
                            MatchKind.COLUMN_ALIAS, 1.0, List.of(tableName)));
                }
                // 枚举值的 label 单独成一条：命中「已取消」时，
                // 它比命中「订单」更精确——它同时确定了表和列的取值。
                for (Glossary.EnumValue enumValue : columnEntry.enumValues()) {
                    if (enumValue.label() != null && !enumValue.label().isBlank()) {
                        addTerm(byTerm, enumValue.label(), new MatchTerm(
                                enumValue.label().toLowerCase(Locale.ROOT),
                                MatchKind.ENUM_LABEL, 1.0, List.of(tableName)));
                    }
                }
            });
        });

        // 表名与列名本身也作为标识符参与匹配（下划线拆成空格，方便英文问法）。
        for (SchemaContext.Table table : catalog.full().tables()) {
            addTerm(byTerm, table.name(), new MatchTerm(table.name().toLowerCase(Locale.ROOT),
                    MatchKind.IDENTIFIER, 1.0, List.of(table.name())));
            for (SchemaContext.Column column : table.columns()) {
                addTerm(byTerm, column.name(), new MatchTerm(column.name().toLowerCase(Locale.ROOT),
                        MatchKind.IDENTIFIER, 1.0, List.of(table.name())));
            }
        }

        return byTerm.values().stream()
                .map(this::mergeTerms)
                .sorted(Comparator.comparingInt((MatchTerm t) -> t.term().length()).reversed())
                .toList();
    }

    /** 同一个词的多条归属合并成一条，权重取各类命中中的最大值。 */
    private MatchTerm mergeTerms(List<MatchTerm> sameTerm) {
        MatchTerm first = sameTerm.getFirst();
        Set<String> tables = new LinkedHashSet<>();
        MatchKind bestKind = first.kind();
        double bestWeight = first.weight();
        for (MatchTerm term : sameTerm) {
            tables.addAll(term.tables());
            if (term.kind().weight() > bestKind.weight()) {
                bestKind = term.kind();
                bestWeight = term.weight();
            }
        }
        return new MatchTerm(first.term(), bestKind, bestWeight, List.copyOf(tables));
    }

    private void addTerm(Map<String, List<MatchTerm>> byTerm, String term, MatchTerm entry) {
        if (term == null || term.isBlank()) {
            return;
        }
        byTerm.computeIfAbsent(term.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(entry);
    }

    /**
     * 关系图，惰性构建一次后复用。
     *
     * <p>阶段 3 把「有哪些 join 边」收敛到 {@link JoinGraph} 一处定义。
     * 在这之前，检索器内部自己拼邻接表、{@code HybridSchemaProvider} 又按
     * 真实外键过滤一遍，两处口径不同——检索器知道
     * {@code customers.customer_state -> regions.region_code}，模型却看不到，
     * 于是 T4-015 猜成了 {@code region_name}（SQL 能跑、结果错）。
     *
     * <p>现在两边共用同一个 {@link JoinGraph} 实例，结构上不可能再分叉。
     */
    private JoinGraph joinGraph() {
        JoinGraph local = joinGraph;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (joinGraph == null) {
                joinGraph = JoinGraph.from(catalog.full(), glossaryLoader.get());
            }
            return joinGraph;
        }
    }

    private static boolean containsIdentifier(String text, String identifier) {
        String lower = identifier.toLowerCase(Locale.ROOT);
        return text.contains(lower) || text.contains(lower.replace('_', ' '));
    }

    private static void addScore(Map<String, Double> scores, String table, double delta) {
        scores.merge(table, delta, Double::sum);
    }

    private static void addEvidence(Map<String, List<String>> evidence, String table, String note) {
        evidence.computeIfAbsent(table, k -> new ArrayList<>()).add(note);
    }

    /**
     * 一个可匹配的词条。
     *
     * @param term   小写后的词，如「订单明细」
     * @param kind   命中类型，决定基础权重
     * @param weight 权重系数，来自 {@link MatchKind}
     * @param tables 命中后应当加分的表（通常一张，枚举/通用词可能多张）
     */
    private record MatchTerm(String term, MatchKind kind, double weight, List<String> tables) {
    }

    /**
     * 命中类型及其权重。
     *
     * <p>权重排序的依据是「这个命中携带了多少确定信息」：
     *
     * <ul>
     *   <li>{@code ENUM_LABEL} 最重：命中「已取消」不仅确定了表，还确定了
     *       列的取值是 {@code canceled}，信息量最大。</li>
     *   <li>{@code TABLE_ALIAS} 次之：明确指向一张表。</li>
     *   <li>{@code COLUMN_ALIAS} 再次：指向某表的一个列，但列名可能跨表重名
     *       （{@code order_id} 在五六张表里都有），确定性略低。</li>
     *   <li>{@code IDENTIFIER} 最低：英文标识符可能是巧合出现。</li>
     * </ul>
     *
     * <p>这些权重是可调的，但**不要凭感觉调**——调参要有评估集数字支撑，
     * 否则就是在过拟合评估集。这也是 ROADMAP 5.5 强调「评估集不能用来调
     * prompt」的同一件事：调参只能看开发集。
     */
    private enum MatchKind {
        ENUM_LABEL("枚举值", 3.0),
        TABLE_ALIAS("表名", 2.5),
        COLUMN_ALIAS("字段", 2.0),
        IDENTIFIER("标识符", 1.5);

        private final String label;
        private final double weight;

        MatchKind(String label, double weight) {
            this.label = label;
            this.weight = weight;
        }

        String label() {
            return label;
        }

        double weight() {
            return weight;
        }
    }
}
