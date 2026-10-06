package com.text2sql.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 每次模型调用的落地记录（阶段 6 第 1 条）。
 *
 * <p><b>为什么不接 Langfuse，而是自己写 JSONL</b>
 *
 * <p>ROADMAP 写的是「接入可观测平台（Langfuse 或自建表）」。选自建的理由：
 * 引入 Langfuse 要多一个服务、一次 SDK 依赖和一份云端账号，而这一层要回答的问题其实很小——
 * 「每次调用花了多少、哪一次慢、花了多少钱」。一行 JSON 就够。
 *
 * <p>这也符合项目一贯的取舍：能被审查的文本文件优先于外部服务。JSONL 可以直接
 * {@code Get-Content} 看、可以 grep、可以用脚本聚合，不依赖任何平台的存活。
 * 等真的需要看板了再迁，记录格式已经稳定。
 *
 * <p><b>为什么不写完整 prompt 和模型回复</b>
 *
 * <p>这是刻意的隐私取舍。prompt 里含用户原始问题，回复里含生成的 SQL——
 * 落到磁盘上就成了不受控的数据副本。默认只记**规模**（字符数、token 数、耗时、成本）
 * 和模型名，足够回答成本与性能问题。
 *
 * <p>需要复现某次具体调用时，把 {@code agent.observability.include-content}
 * 打开一次即可——但它是个开关而不是默认，这样「不小心把用户问题写进日志」
 * 这件事需要显式决定，而不是默认发生。
 *
 * <p><b>写失败不能影响主链路</b>：观测是旁路。磁盘满、目录没权限这类问题
 * 只记一条 warn，绝不能让一次正常的问数因为写日志失败而失败。
 */
@Component
public class LlmCallLog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLog.class);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final AgentProperties properties;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    private BufferedWriter writer;
    private Path currentFile;
    private int written;

    /**
     * 落库用的连接。为 null 表示只写文件。
     *
     * <p>为什么同时保留文件与数据库两份：它们回答的问题不同。
     * 文件是**原始记录**，一行一次调用，出问题时可以整份带走、直接 grep；
     * 数据库表是**可查询的记录**，能按模型、按天聚合成本。
     * 只留文件则聚合要靠脚本，只留数据库则排查时没法整份取证。
     *
     * <p>落库走的是只写账号（{@code text2sql_rw}），与业务只读账号分开，
     * 理由见 {@code data/schema/06_persistence.sql}。
     */
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public LlmCallLog(AgentProperties properties) {
        this.properties = properties;
        this.db = com.text2sql.agent.persistence.PersistenceSupport
                .jdbcTemplateOrNull(properties, "调用日志");
    }

    public boolean persisted() {
        return db != null;
    }

    public boolean enabled() {
        return properties.getObservability().isEnabled();
    }

    /**
     * 记一次调用。
     *
     * @param record    调用账目（token / 耗时 / 成本）
     * @param status    这次调用的结果状态，用于区分「慢且失败」和「慢但成功」
     * @param prompt    完整 prompt；仅在 include-content 打开时写入
     * @param response  模型原始回复；同上
     */
    public synchronized void record(LlmCallRecord record, String status, String prompt, String response) {
        if (!enabled() || record == null) {
            return;
        }
        try {
            Entry entry = new Entry(
                    LocalDateTime.now().format(TS),
                    record.model(),
                    status,
                    record.promptTokens(),
                    record.completionTokens(),
                    record.totalTokens(),
                    record.promptChars(),
                    record.latencyMs(),
                    record.costYuan(),
                    properties.getObservability().isIncludeContent() ? prompt : null,
                    properties.getObservability().isIncludeContent() ? response : null);
            writer().write(jsonMapper.writeValueAsString(entry));
            writer().newLine();
            writer().flush();
            written++;
            recordToDatabase(entry);
        } catch (IOException e) {
            // 观测是旁路：写不进去只告警，不影响这次问数。
            log.warn("LLM 调用记录写入失败（不影响主链路）：{}", e.getMessage());
        }
    }

    /**
     * 写数据库。失败只告警——和写文件一样，观测是旁路。
     *
     * <p>顺带说明为什么日志表只给 INSERT 和 SELECT、不给 UPDATE/DELETE：
     * 审计记录的完整性靠的是「写进去就改不掉」，而不是靠代码自觉不去改它。
     */
    private void recordToDatabase(Entry entry) {
        if (db == null) {
            return;
        }
        try {
            db.update("INSERT INTO llm_call_log(model, status, prompt_tokens, completion_tokens, "
                            + "total_tokens, prompt_chars, latency_ms, cost_yuan) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    entry.model(), entry.status(), entry.promptTokens(), entry.completionTokens(),
                    entry.totalTokens(), entry.promptChars(), entry.latencyMs(), entry.costYuan());
        } catch (Exception e) {
            log.warn("调用记录落库失败（不影响主链路）：{}", e.getMessage());
        }
    }

    public int written() {
        return written;
    }

    private BufferedWriter writer() throws IOException {
        String stamp = LocalDateTime.now().format(FILE_STAMP);
        Path dir = Path.of(properties.getObservability().getDir());
        Path target = dir.resolve("llm-calls-" + stamp + ".jsonl");
        if (writer == null || !target.equals(currentFile)) {
            close();
            Files.createDirectories(dir);
            writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            currentFile = target;
            log.info("LLM 调用记录写入：{}", target.toAbsolutePath());
        }
        return writer;
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.warn("关闭 LLM 调用记录失败：{}", e.getMessage());
            }
            writer = null;
        }
    }

    /** 一行 JSON。字段名与评估报告保持一致，方便两个数据源对照。 */
    public record Entry(
            String timestamp,
            String model,
            String status,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int promptChars,
            long latencyMs,
            double costYuan,
            String prompt,
            String response) {
    }
}
