package com.text2sql.agent.retrieval.glossary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.text2sql.agent.retrieval.SchemaCatalog;
import com.text2sql.agent.retrieval.SchemaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 加载并**校验**中文语义词典。
 *
 * <p><b>为什么校验是这一步的核心，而不是可选的好习惯</b>
 *
 * <p>词典是纯文本，人一定会写错。而写错的后果是静默的：把
 * {@code order_status} 敲成 {@code order_stauts}，检索不会报错，
 * 它只会永远匹配不上——表现为「某些问题的表召回率莫名其妙地低」。
 * 这类 bug 的排查成本极高，因为没有任何错误信息指向词典。
 *
 * <p>所以这里在启动时把词典和真实 schema 对一遍：表名、列名必须存在，
 * 关系层的两端必须存在。对不上的条目不静默忽略，而是**记警告并统计**，
 * 启动日志里能直接看到「词典有 2 条无效条目」。这是把「配置错误」
 * 从运行时问题提前成启动时问题。
 *
 * <p>被否掉的方案：启动即抛异常。它更严格，但会让「数据库还没建好」
 * 和「词典写错了」变成同一个失败——而前者是正常开发状态（阶段 0 之前
 * 就没有库）。记警告不阻断，同时让错误可见，是这里更合适的选择。
 *
 * <p>解析器同样刻意不注册成 Spring Bean，理由见 {@code EvalItemLoader}：
 * 容器里出现第二个 ObjectMapper 会劫持 REST 的 JSON 序列化。
 */
@Component
public class GlossaryLoader {

    private static final Logger log = LoggerFactory.getLogger(GlossaryLoader.class);

    /** 词典文件路径。放 resources 下，打包进 jar，不依赖工作目录。 */
    private static final String RESOURCE = "schema/glossary.yml";

    private final ObjectMapper yamlObjectMapper = new ObjectMapper(new YAMLFactory());
    private final SchemaCatalog catalog;

    private volatile Glossary cached;

    public GlossaryLoader(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 取词典，首次调用时加载并校验。
     *
     * <p>不用 {@code @PostConstruct} 预热：词典只有检索版 provider 需要，
     * 跑 baseline 时完全用不上。让它懒加载，baseline 的启动路径就少一个
     * 可能失败的环节——这符合「优化必须是显式开启的」这个整体设计。
     */
    public Glossary get() {
        Glossary local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    local = load();
                    cached = local;
                }
            }
        }
        return local;
    }

    private Glossary load() {
        Glossary raw = readResource();
        Glossary validated = validate(raw);
        log.info("语义词典已加载：{} 张表有中文描述 / {} 条约定关联（无效条目 {} 条）",
                validated.tableCount(), validated.relations().size(),
                countInvalid(raw, validated));
        return validated;
    }

    private Glossary readResource() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.warn("未找到语义词典 {}，检索将退化为纯标识符匹配", RESOURCE);
            return Glossary.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            Glossary glossary = yamlObjectMapper.readValue(in, Glossary.class);
            return glossary == null ? Glossary.empty() : glossary;
        } catch (IOException e) {
            throw new UncheckedIOException("语义词典解析失败：" + RESOURCE, e);
        }
    }

    /**
     * 用真实 schema 校验词典，丢掉对不上的条目。
     *
     * <p>校验粒度是「列级」而不是「表级」：只验证表存在是不够的，
     * 因为绝大多数错误是列名写错，而列名写错恰恰是最难从现象反推的。
     */
    private Glossary validate(Glossary raw) {
        Map<String, SchemaContext.Table> schemaTables = catalog.tablesByName();
        Map<String, Glossary.TableEntry> keptTables = new LinkedHashMap<>();

        raw.tables().forEach((tableName, entry) -> {
            SchemaContext.Table real = schemaTables.get(tableName.toLowerCase(Locale.ROOT));
            if (real == null) {
                log.warn("词典无效条目：表 {} 不存在于数据库，已忽略", tableName);
                return;
            }
            Set<String> realColumns = new LinkedHashSet<>();
            real.columns().forEach(c -> realColumns.add(c.name().toLowerCase(Locale.ROOT)));

            Map<String, Glossary.ColumnEntry> keptColumns = new LinkedHashMap<>();
            entry.columns().forEach((columnName, columnEntry) -> {
                if (!realColumns.contains(columnName.toLowerCase(Locale.ROOT))) {
                    log.warn("词典无效条目：{}.{} 不存在于数据库，已忽略", tableName, columnName);
                    return;
                }
                keptColumns.put(columnName, columnEntry);
            });
            keptTables.put(real.name(), new Glossary.TableEntry(entry.aliases(), entry.comment(), keptColumns));
        });

        List<Glossary.Relation> keptRelations = new ArrayList<>();
        for (Glossary.Relation relation : raw.relations()) {
            if (endpointExists(relation.from(), schemaTables) && endpointExists(relation.to(), schemaTables)) {
                keptRelations.add(relation);
            } else {
                log.warn("词典无效关联：{} -> {}，端点不存在，已忽略", relation.from(), relation.to());
            }
        }

        return new Glossary(keptRelations, keptTables);
    }

    /** 校验 {@code "表.列"} 形式的端点是否真实存在。 */
    private boolean endpointExists(String endpoint, Map<String, SchemaContext.Table> schemaTables) {
        if (endpoint == null || !endpoint.contains(".")) {
            return false;
        }
        int dot = endpoint.indexOf('.');
        String tableName = endpoint.substring(0, dot).toLowerCase(Locale.ROOT);
        String columnName = endpoint.substring(dot + 1).toLowerCase(Locale.ROOT);
        SchemaContext.Table table = schemaTables.get(tableName);
        if (table == null) {
            return false;
        }
        return table.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(columnName));
    }

    private int countInvalid(Glossary raw, Glossary validated) {
        int invalidTables = raw.tableCount() - validated.tableCount();
        int invalidColumns = 0;
        for (Map.Entry<String, Glossary.TableEntry> e : raw.tables().entrySet()) {
            Glossary.TableEntry kept = validated.tables().get(e.getKey());
            if (kept != null) {
                invalidColumns += e.getValue().columns().size() - kept.columns().size();
            }
        }
        int invalidRelations = raw.relations().size() - validated.relations().size();
        return invalidTables + invalidColumns + invalidRelations;
    }
}
