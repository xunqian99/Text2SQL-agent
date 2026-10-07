package com.text2sql.agent.session;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class QuestionRewriterTest {

    private QuestionRewriter rewriter;
    private AgentProperties properties;
    private LlmSqlGenerator generator;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getConversation().setEnabled(true);
        generator = mock(LlmSqlGenerator.class);
        rewriter = new QuestionRewriter(generator, properties);
    }

    @Test
    @DisplayName("空会话或首个问题不应被识别为追问")
    void testNotFollowUpWithoutContext() {
        ConversationSession session = new ConversationSession("s1");
        assertThat(rewriter.isFollowUp("2018年有多少订单？", session)).isFalse();
    }

    @Test
    @DisplayName("典型前缀或代词应被识别为追问")
    void testFollowUpIdentification() {
        ConversationSession session = new ConversationSession("s1");
        session.addTurn(ConversationTurn.of(1, "2018年销售额最高的5个州？",
                "2018年销售额最高的5个州？", "SELECT ...", "SUCCESS", "5 rows"), 5);

        assertThat(rewriter.isFollowUp("那2017年的呢？", session)).isTrue();
        assertThat(rewriter.isFollowUp("只看前3名", session)).isTrue();
        assertThat(rewriter.isFollowUp("换成圣保罗州", session)).isTrue();
        assertThat(rewriter.isFollowUp("这些订单的平均运费是多少？", session)).isTrue();
        assertThat(rewriter.isFollowUp("按订单量降序", session)).isTrue();
    }

    @Test
    @DisplayName("规则改写：时间年份精准替换")
    void testRuleRewriteYearSwap() {
        ConversationSession session = new ConversationSession("s1");
        session.addTurn(ConversationTurn.of(1, "2018年销售额最高的5个州是哪些？",
                "2018年销售额最高的5个州是哪些？", "SELECT ...", "SUCCESS", "5 rows"), 5);

        String rewritten = rewriter.rewrite("那2017年的呢？", session);
        assertThat(rewritten).isEqualTo("2017年销售额最高的5个州是哪些？");
    }

    @Test
    @DisplayName("规则改写：限定数量替换或追加")
    void testRuleRewriteLimit() {
        ConversationSession session = new ConversationSession("s1");
        session.addTurn(ConversationTurn.of(1, "各品类销售额前10名",
                "各品类销售额前10名", "SELECT ...", "SUCCESS", "10 rows"), 5);

        String rewritten = rewriter.rewrite("只看前3名", session);
        assertThat(rewritten).contains("前 3 名");
    }

    @Test
    @DisplayName("规则改写：条件变更与语气词剥离")
    void testRuleRewriteConditionSwitch() {
        ConversationSession session = new ConversationSession("s1");
        session.addTurn(ConversationTurn.of(1, "圣保罗州的卖家有多少个？",
                "圣保罗州的卖家有多少个？", "SELECT ...", "SUCCESS", "1 row"), 5);

        String rewritten = rewriter.rewrite("换成里约热内卢州", session);
        assertThat(rewritten).isEqualTo("圣保罗州的卖家有多少个？，条件换成 里约热内卢州");

        String rewritten2 = rewriter.rewrite("那买家呢？", session);
        assertThat(rewritten2).isEqualTo("圣保罗州的卖家有多少个？，买家");
    }

    @Test
    @DisplayName("追问在具备前置 Schema 时允许 0ms 上下文复用")
    void testCanReuseSchema() {
        ConversationSession session = new ConversationSession("s1");
        SchemaContext dummySchema = new SchemaContext(
                List.of(new SchemaContext.Table("orders", null, List.of())),
                List.of(), "", "CREATE TABLE orders;");
        session.setLastSchema(dummySchema);
        session.addTurn(ConversationTurn.of(1, "2018年订单量", "2018年订单量",
                "SELECT ...", "SUCCESS", "1"), 5);

        assertThat(rewriter.canReuseSchema("那2017年的呢？", session)).isTrue();
    }
}
