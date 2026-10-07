package com.text2sql.agent.fewshot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.text2sql.agent.evaluation.TableRecall;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 加载 classpath:fewshot/examples.yml 中的 Few-shot 示例，
 * 并在启动时自动解析每条示例 SQL 涉及的真实表名。
 */
@Component
public class ExampleLoader {

    private static final Logger log = LoggerFactory.getLogger(ExampleLoader.class);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private List<Example> examples = Collections.emptyList();

    @PostConstruct
    public void init() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource resource = resolver.getResource("classpath:fewshot/examples.yml");
            if (!resource.exists()) {
                log.warn("Few-shot 示例文件不存在：classpath:fewshot/examples.yml");
                return;
            }

            try (InputStream in = resource.getInputStream()) {
                List<Map<String, Object>> raw = yamlMapper.readValue(in, new TypeReference<>() {});
                List<Example> loaded = new ArrayList<>();
                for (Map<String, Object> map : raw) {
                    String id = String.valueOf(map.getOrDefault("id", ""));
                    String difficulty = String.valueOf(map.getOrDefault("difficulty", ""));
                    String question = String.valueOf(map.getOrDefault("question", ""));
                    String sql = String.valueOf(map.getOrDefault("sql", ""));

                    var tables = TableRecall.expectedTables(sql);
                    loaded.add(new Example(id, difficulty, question, sql, tables));
                }
                this.examples = List.copyOf(loaded);
                log.info("Few-shot 示例库加载完成：{} 条示例", this.examples.size());
            }
        } catch (Exception e) {
            log.error("加载 Few-shot 示例库失败", e);
        }
    }

    public List<Example> getExamples() {
        return examples;
    }
}
