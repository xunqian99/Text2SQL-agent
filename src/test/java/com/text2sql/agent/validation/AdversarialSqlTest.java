package com.text2sql.agent.validation;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对抗性用例（阶段 5 产出物）。
 *
 * <p><b>为什么单独一个类，而不是并进 SqlValidatorTest</b>
 *
 * <p>{@code SqlValidatorTest} 测的是「功能对不对」——合法的放行、已知的拦下。
 * 这里测的是「**假设模型是恶意的或被人操纵的**」：攻击者会刻意构造
 * 「最外层看起来完全无害」的语句。两类测试的思维模式不同，混在一起会互相淹没。
 *
 * <p><b>这一层的定位</b>：它不是唯一防线，也不该是。ROADMAP 阶段 5 的三层是
 * AST 校验（这里）→ 只读账号 → 超时与行数上限。任何一层单独都不够：
 * 校验可能被绕过，账号权限能兜住最坏情况，超时能挡住耗时攻击。
 * 用「不依赖任何单一层正确」来描述这个设计，比说「我校验做得严」更站得住。
 *
 * <p>注意所有这些用例**都不连数据库**：校验是纯计算，所以安全测试可以在
 * 任何环境跑，包括没装 PostgreSQL 的机器。
 */
class AdversarialSqlTest {

    private SqlValidator validator;
    private SchemaContext schema;

    @BeforeEach
    void setUp() {
        validator = new SqlValidator(new AgentProperties());
        schema = new SchemaContext(
                List.of(
                        new SchemaContext.Table("orders", "订单表", List.of(
                                new SchemaContext.Column("order_id", "text", false, null),
                                new SchemaContext.Column("customer_id", "text", false, null),
                                new SchemaContext.Column("order_status", "text", false, null))),
                        new SchemaContext.Table("sellers", "卖家表", List.of(
                                new SchemaContext.Column("seller_id", "text", false, null),
                                new SchemaContext.Column("seller_state", "text", false, null))),
                        new SchemaContext.Table("regions", "区域表", List.of(
                                new SchemaContext.Column("region_code", "text", false, null),
                                new SchemaContext.Column("region_name", "text", false, null)))),
                List.of(),
                "",
                "");
    }

    // ------------------------------------------------------------------
    // 1. 写操作：靠「必须是 SELECT」一条挡住全部
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "写操作被拦截：{0}")
    @ValueSource(strings = {
            "DROP TABLE orders",
            "DELETE FROM orders",
            "UPDATE orders SET order_status = 'x'",
            "INSERT INTO orders (order_id) VALUES ('1')",
            "TRUNCATE TABLE orders",
            "ALTER TABLE orders ADD COLUMN hacked text",
            "CREATE TABLE backdoor (id text)",
            "GRANT ALL ON orders TO PUBLIC",
            "REVOKE SELECT ON orders FROM text2sql",
            "CREATE INDEX idx ON orders (order_id)",
    })
    void rejectsEveryWriteOperation(String sql) {
        ValidationResult result = validator.validate(sql, schema);

        assertThat(result.valid()).as("必须拦下：%s", sql).isFalse();
        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .containsAnyOf(ValidationResult.Code.NOT_SELECT,
                        ValidationResult.Code.MULTIPLE_STATEMENTS,
                        ValidationResult.Code.PARSE_ERROR);
    }

    // ------------------------------------------------------------------
    // 2. 多语句注入：SELECT 打头，破坏性语句跟在后面
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "多语句注入被拦截：{0}")
    @ValueSource(strings = {
            "SELECT 1; DROP TABLE orders",
            "SELECT order_id FROM orders; DELETE FROM orders",
            "SELECT order_id FROM orders LIMIT 1; UPDATE orders SET order_status='x'",
            "SELECT order_id FROM orders;; DROP TABLE orders",
    })
    void rejectsChainedStatements(String sql) {
        ValidationResult result = validator.validate(sql, schema);

        assertThat(result.valid()).as("必须拦下：%s", sql).isFalse();
        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.MULTIPLE_STATEMENTS);
    }

    @Test
    @DisplayName("字符串字面量里的分号不算多语句——否则正常查询会被误杀")
    void doesNotMistakeSemicolonInsideLiteralForInjection() {
        ValidationResult result = validator.validate(
                "SELECT order_id FROM orders WHERE order_status = 'a;b' LIMIT 10", schema);

        assertThat(result.valid()).isTrue();
    }

    // ------------------------------------------------------------------
    // 3. 只读但有副作用：这类攻击「只允许 SELECT」完全挡不住
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "危险函数被拦截：{0}")
    @ValueSource(strings = {
            "SELECT pg_read_file('/etc/passwd') LIMIT 10",
            "SELECT pg_read_binary_file('/var/lib/postgresql/data/postgresql.conf') LIMIT 10",
            "SELECT pg_ls_dir('/') LIMIT 10",
            "SELECT pg_sleep(60) LIMIT 1",
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity LIMIT 10",
            "SELECT lo_import('/etc/passwd') LIMIT 10",
            "SELECT dblink('host=evil', 'SELECT 1') LIMIT 10",
            "SELECT set_config('log_statement', 'all', false) LIMIT 10",
            "SELECT nextval('orders_order_id_seq') LIMIT 10",
            "SELECT order_id, pg_sleep(60) FROM orders LIMIT 10",
            "SELECT order_id FROM orders WHERE pg_sleep(60) IS NOT NULL LIMIT 10",
    })
    void rejectsDangerousFunctions(String sql) {
        ValidationResult result = validator.validate(sql, schema);

        assertThat(result.valid()).as("必须拦下：%s", sql).isFalse();
    }

    @Test
    @DisplayName("危险函数藏在 CTE 里也要拦下——最外层看着完全无害")
    void rejectsDangerousFunctionInsideCte() {
        ValidationResult result = validator.validate(
                "WITH slow AS (SELECT pg_sleep(60)) SELECT order_id FROM orders LIMIT 10", schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.FORBIDDEN_FUNCTION);
    }

    @Test
    @DisplayName("危险函数藏在子查询里也要拦下")
    void rejectsDangerousFunctionInsideSubquery() {
        ValidationResult result = validator.validate(
                "SELECT order_id FROM orders WHERE order_id IN "
                        + "(SELECT pg_read_file('/etc/passwd')) LIMIT 10", schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.FORBIDDEN_FUNCTION);
    }

    // ------------------------------------------------------------------
    // 4. 系统目录探测：借 information_schema / pg_catalog 摸清库结构
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "系统目录被表白名单拦住：{0}")
    @ValueSource(strings = {
            "SELECT table_name FROM information_schema.tables LIMIT 10",
            "SELECT usename, passwd FROM pg_catalog.pg_shadow LIMIT 10",
            "SELECT * FROM pg_catalog.pg_settings LIMIT 10",
            "SELECT rolname FROM pg_roles LIMIT 10",
    })
    void rejectsSystemCatalogProbing(String sql) {
        ValidationResult result = validator.validate(sql, schema);

        assertThat(result.valid()).as("必须拦下：%s", sql).isFalse();
        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.UNKNOWN_TABLE);
    }

    // ------------------------------------------------------------------
    // 5. 列级白名单：模型猜错维度表时提前暴露，而不是等数据库报错
    // ------------------------------------------------------------------

    @Test
    @DisplayName("跨表用错列被拦截：sellers 没有 region_name")
    void rejectsColumnFromWrongTable() {
        ValidationResult result = validator.validate(
                "SELECT s.region_name FROM sellers s LIMIT 10", schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.UNKNOWN_COLUMN);
    }

    @Test
    @DisplayName("列存在时不误报")
    void allowsColumnThatExistsOnThatTable() {
        ValidationResult result = validator.validate(
                "SELECT s.seller_state, r.region_name FROM sellers s "
                        + "JOIN regions r ON r.region_code = s.seller_state LIMIT 10", schema);

        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("CTE 输出列不误报——它不属于任何 schema 表")
    void doesNotFlagCteColumns() {
        ValidationResult result = validator.validate(
                "WITH monthly AS (SELECT order_status, COUNT(*) AS cnt FROM orders GROUP BY 1) "
                        + "SELECT m.order_status, m.cnt FROM monthly m LIMIT 10", schema);

        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("派生表列不误报")
    void doesNotFlagDerivedTableColumns() {
        ValidationResult result = validator.validate(
                "SELECT t.cnt FROM (SELECT COUNT(*) AS cnt FROM orders) t LIMIT 10", schema);

        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("裸列名不检查——这是刻意留下的空缺，记录在这里免得被误认为漏测")
    void doesNotCheckUnqualifiedColumns() {
        // 裸列名的合法来源太多（SELECT 别名、CTE 列、派生表列、集合操作），
        // 做严格检查会把正常查询拦下。所以只做带前缀的那一半。
        ValidationResult result = validator.validate(
                "SELECT nonexistent_column FROM orders LIMIT 10", schema);

        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("同一别名跨作用域指向不同关系时不误杀——这条是 dry-run 抓出来的真实 bug")
    void doesNotFlagAliasReusedAcrossScopes() {
        // 取自 T6-016 的标准 SQL 结构：外层 bp 是 brands，CTE 里 bp 是 brand_products。
        // 如果按扁平表解析别名，bp.product_id 会被判成「brands 没有 product_id」，
        // 正常查询被拦下——这正是列白名单最容易犯的错。
        String sql = """
                WITH bp AS (
                  SELECT b.seller_id AS seller_id, 1 AS product_id
                  FROM sellers b
                )
                SELECT b.seller_state, x.product_id
                FROM sellers b
                JOIN bp x ON x.seller_id = b.seller_id
                LIMIT 10
                """;

        ValidationResult result = validator.validate(sql, schema);

        assertThat(result.valid()).as("别名跨作用域复用不能误判：%s", result.violations()).isTrue();
    }

    @Test
    @DisplayName("列白名单可以关掉，用于复现历史数字")
    void columnWhitelistCanBeDisabled() {
        AgentProperties off = new AgentProperties();
        off.getGuard().setColumnWhitelistEnabled(false);
        SqlValidator lenient = new SqlValidator(off);

        ValidationResult result = lenient.validate(
                "SELECT s.region_name FROM sellers s LIMIT 10", schema);

        assertThat(result.valid()).isTrue();
    }

    // ------------------------------------------------------------------
    // 6. 解析层：畸形输入必须 fail-closed，不能静默放行
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "畸形输入被拒绝：{0}")
    @ValueSource(strings = {
            "",
            "   ",
            "这不是 SQL",
            "SELECT FROM WHERE",
            "/* 只有注释 */",
    })
    void rejectsMalformedInput(String sql) {
        ValidationResult result = validator.validate(sql, schema);

        assertThat(result.valid()).as("必须拒绝：%s", sql).isFalse();
    }
}
