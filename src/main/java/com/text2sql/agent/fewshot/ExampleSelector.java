package com.text2sql.agent.fewshot;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 负责根据当前请求的命中表及问题信息，动态挑选最相关的 Few-shot 示例。
 *
 * <p>选择策略（确定性规则，零额外依赖）：
 * 1. 过滤掉与当前问题完全一致的示例（避免测试集泄漏或自指）
 * 2. 计算候选示例的 tables 与当前上下文命中表的 Jaccard / 共同表数量
 * 3. 共同表数 > 0 的优先入选；若同分，按共同表数降序 -> 示例涉及表数接近度 -> 示例 ID 字母序
 * 4. 截取 Top-K（默认 3）
 */
@Component
public class ExampleSelector {

    private final ExampleLoader exampleLoader;

    public ExampleSelector(ExampleLoader exampleLoader) {
        this.exampleLoader = exampleLoader;
    }

    /**
     * 为当前问题与上下文命中表选择最匹配的示例。
     *
     * @param question 当前用户问题
     * @param currentTables 当前已召回/命中的表名集合（小写）
     * @param maxExamples 最大示例数
     * @return 排序后的示例列表
     */
    public List<Example> select(String question, Set<String> currentTables, int maxExamples) {
        if (maxExamples <= 0) {
            return List.of();
        }
        List<Example> all = exampleLoader.getExamples();
        if (all.isEmpty()) {
            return List.of();
        }

        String normQ = question == null ? "" : question.strip();
        Set<String> targetTables = new HashSet<>();
        if (currentTables != null) {
            for (String t : currentTables) {
                if (t != null && !t.isBlank()) {
                    targetTables.add(t.toLowerCase(Locale.ROOT));
                }
            }
        }

        // 评分逻辑
        record Scored(Example example, int overlap, double jaccard) {}

        List<Scored> scoredList = new ArrayList<>();
        for (Example ex : all) {
            if (ex.question().strip().equalsIgnoreCase(normQ)) {
                // 跳过完全相同的题目，防止 dev 泄漏
                continue;
            }
            int overlap = 0;
            for (String t : ex.tables()) {
                if (targetTables.contains(t.toLowerCase(Locale.ROOT))) {
                    overlap++;
                }
            }
            double union = targetTables.size() + ex.tables().size() - overlap;
            double jaccard = union <= 0 ? 0.0 : ((double) overlap / union);

            scoredList.add(new Scored(ex, overlap, jaccard));
        }

        // 排序规则：共同表数量降序 -> Jaccard 相似度降序 -> 示例 ID 字母序稳定排序
        scoredList.sort((a, b) -> {
            int c1 = Integer.compare(b.overlap(), a.overlap());
            if (c1 != 0) return c1;
            int c2 = Double.compare(b.jaccard(), a.jaccard());
            if (c2 != 0) return c2;
            return a.example().id().compareTo(b.example().id());
        });

        // 优先只选至少有 1 张表重叠的；如果全部没有重叠，则降级按通用高质量示例返回
        List<Example> selected = new ArrayList<>();
        for (Scored s : scoredList) {
            if (s.overlap() > 0) {
                selected.add(s.example());
                if (selected.size() >= maxExamples) {
                    break;
                }
            }
        }

        // 如果重叠表不足，且要求必须提供示例，则补充通用示例
        if (selected.size() < maxExamples) {
            for (Scored s : scoredList) {
                if (!selected.contains(s.example())) {
                    selected.add(s.example());
                    if (selected.size() >= maxExamples) {
                        break;
                    }
                }
            }
        }

        return List.copyOf(selected);
    }
}
