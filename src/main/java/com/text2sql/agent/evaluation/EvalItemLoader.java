package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.text2sql.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 从 {@code data/eval/T*.yaml} 加载评估集。
 *
 * <p>按文件名排序而不是目录枚举顺序：{@code T1} 到 {@code T6} 的字典序
 * 恰好等于难度递增序，所以排序后评估报告里的失败案例是「从简单到复杂」
 * 排列的，扫一眼就能看出问题集中在哪一层。依赖文件系统的枚举顺序
 * 会让同一份代码在不同机器上产出不同顺序的报告。
 */
@Component
public class EvalItemLoader {

    /**
     * 评估集专用 YAML 解析器，**刻意不注册成 Spring Bean**。
     *
     * <p>被否掉的方案：定义一个 {@code @Bean ObjectMapper}（YAML 工厂）供注入。
     * 它有一个隐蔽的副作用——Spring Boot 的 Jackson 自动配置带
     * {@code @ConditionalOnMissingBean(ObjectMapper.class)}，一旦容器里存在
     * ObjectMapper，REST 接口的消息转换器就会改用这个 YAML 实例，
     * 于是 {@code /api/ask} 返回的 JSON 会变成 YAML 格式，前端解析直接失败。
     *
     * <p>这个 bug 的恶劣之处在于：接口看着「有响应」，只是格式不对，
     * 排查时容易往「前端怎么解析」的方向找，而根因在启动装配里。
     * 把它做成局部变量，副作用就消失了。
     */
    private final ObjectMapper yamlObjectMapper = new ObjectMapper(new YAMLFactory());

    private final AgentProperties properties;

    public EvalItemLoader(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 加载评估集。
     *
     * <p>筛选顺序是「先按难度层过滤，再截断」。反过来会得到一个反直觉的结果：
     * 想只跑 T1、T2 却先取前 50 条，那 50 条可能全落在 T1 里，T2 一条都没跑到。
     */
    public List<EvalItem> load() {
        return load(properties.getEval().getDir(), properties.getEval().getLayers(),
                properties.getEval().getLimit());
    }

    public List<EvalItem> load(String dir, List<String> layers, int limit) {
        Path base = Path.of(dir);
        if (!Files.isDirectory(base)) {
            throw new IllegalStateException("评估集目录不存在：" + base.toAbsolutePath());
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(base)) {
            files = stream
                    .filter(p -> p.getFileName().toString().matches("T\\d+_.*\\.ya?ml"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("无法列出评估集目录：" + base.toAbsolutePath(), e);
        }

        List<EvalItem> items = new ArrayList<>();
        for (Path file : files) {
            items.addAll(readFile(file));
        }

        if (layers != null && !layers.isEmpty()) {
            items = items.stream()
                    .filter(item -> layers.stream().anyMatch(layer -> matchesLayer(item, layer)))
                    .toList();
        }

        if (limit > 0 && items.size() > limit) {
            items = items.subList(0, limit);
        }
        return List.copyOf(items);
    }

    private List<EvalItem> readFile(Path file) {
        try {
            List<EvalItem> parsed = yamlObjectMapper.readValue(
                    Files.readString(file),
                    yamlObjectMapper.getTypeFactory().constructCollectionType(List.class, EvalItem.class));
            return parsed == null ? List.of() : parsed;
        } catch (IOException e) {
            throw new UncheckedIOException("评估集解析失败：" + file.toAbsolutePath(), e);
        }
    }

    /**
     * 判断某条评估项是否属于指定难度层。
     *
     * <p>同时匹配难度名（{@code single_table_basic}）和文件名前缀（{@code T1}），
     * 因为命令行上手写 {@code --agent.eval.layers=T1,T2} 比手写完整的
     * 难度名方便得多，而两种写法都有人会用。
     */
    private boolean matchesLayer(EvalItem item, String layer) {
        String normalized = layer.strip();
        if (normalized.isEmpty()) {
            return true;
        }
        if (normalized.equalsIgnoreCase(item.difficulty())) {
            return true;
        }
        return item.id() != null && item.id().toUpperCase().startsWith(normalized.toUpperCase() + "-");
    }
}
