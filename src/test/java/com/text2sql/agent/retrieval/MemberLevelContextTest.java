package com.text2sql.agent.retrieval;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercise the shipped dictionary through retrieval, pruning and the final model message. */
class MemberLevelContextTest {

    @ParameterizedTest
    @ValueSource(strings = {"青铜会员", "白银会员", "黄金会员", "铂金会员"})
    void fullTierNameReachesModelWithItsColumnAndJoinKeys(String tier) {
        var fixture = fixture(true);
        String question = tier + "有多少人？";
        var retrieval = fixture.retriever().retrieve(question);
        assertThat(retrieval.fellBack()).isFalse();
        assertThat(retrieval.tableNames()).contains("members", "member_levels");
        assertThat(retrieval.expandedTables()).doesNotContain("member_levels");
        assertThat(retrieval.evidence().get("member_levels")).anyMatch(s -> s.contains(tier));

        var matches = fixture.values().findMatches(question, Set.copyOf(retrieval.tableNames()));
        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.table()).isEqualTo("member_levels");
            assertThat(match.column()).isEqualTo("level_name");
            assertThat(match.matchedWord()).isEqualTo(tier);
            assertThat(match.value()).isEqualTo(tier);
        });
        var context = fixture.provider().provide(question);
        assertThat(context.tables().stream().filter(t -> t.name().equals("member_levels"))
                .findFirst().orElseThrow().columns()).extracting(SchemaContext.Column::name)
                .contains("level_id", "level_name").doesNotContain("discount_rate");
        assertThat(context.tables().stream().filter(t -> t.name().equals("members"))
                .findFirst().orElseThrow().columns()).extracting(SchemaContext.Column::name)
                .contains("member_id", "level_id");
        assertThat(new PromptTemplate(fixture.properties()).userPrompt(context, question))
                .contains("level_name = '" + tier + "'", "member_levels", "members", "level_id");
    }

    @Test
    void twoTiersRetainDistinctPhysicalValuesOnTheSameColumn() {
        var fixture = fixture(true);
        var context = fixture.provider().provide("黄金会员和白银会员分别有多少人？");
        assertThat(context.valueHints()).contains("level_name = '黄金会员'", "level_name = '白银会员'");
        assertThat(fixture.values().findMatches("黄金会员和白银会员分别有多少人？",
                Set.of("member_levels"))).extracting(ValueRetriever.ValueMatch::value)
                .containsExactlyInAnyOrder("黄金会员", "白银会员");
    }

    @Test
    void genericGoldAndOtherTargetTablesDoNotAcquireTierFilters() {
        var fixture = fixture(true);
        assertThat(fixture.values().findMatches("黄金商品有多少件？", Set.of("member_levels"))).isEmpty();
        assertThat(fixture.values().findMatches("黄金会员有多少人？", Set.of("members"))).isEmpty();
    }

    @Test
    void absentPhysicalColumnCannotProduceAFilterFromTheDictionary() {
        var fixture = fixture(false);
        assertThat(fixture.values().findMatches("黄金会员有多少人？", Set.of("member_levels"))).isEmpty();
        assertThat(fixture.provider().provide("黄金会员有多少人？").hasValueHints()).isFalse();
    }

    private Fixture fixture(boolean withLevelName) {
        var members = new SchemaContext.Table("members", null, List.of(
                column("member_id", true), column("level_id", false), column("points", false)));
        var levelColumns = withLevelName
                ? List.of(column("level_id", true), column("level_name", false), column("discount_rate", false))
                : List.of(column("level_id", true), column("discount_rate", false));
        var levels = new SchemaContext.Table("member_levels", null, levelColumns);
        var full = new SchemaContext(List.of(members, levels), List.of(
                new SchemaContext.ForeignKey("members", "level_id", "member_levels", "level_id")), "", "");
        var catalog = mock(SchemaCatalog.class);
        var byName = new LinkedHashMap<String, SchemaContext.Table>();
        full.tables().forEach(table -> byName.put(table.name(), table));
        when(catalog.full()).thenReturn(full);
        when(catalog.tablesByName()).thenReturn(byName);
        var properties = new AgentProperties();
        properties.getSemantic().setEnabled(false);
        properties.getRetrieval().setColumnPruningEnabled(true);
        properties.getRetrieval().setColumnPruningMinColumns(1);
        var glossary = new GlossaryLoader(catalog);
        var retriever = new LexicalSchemaRetriever(glossary, catalog, properties);
        var values = new ValueRetriever(glossary);
        var provider = new HybridSchemaProvider(catalog, retriever, glossary, properties,
                null, null, values, new ColumnPruner(glossary));
        return new Fixture(properties, retriever, values, provider);
    }

    private SchemaContext.Column column(String name, boolean primaryKey) {
        return new SchemaContext.Column(name, "text", false, null, primaryKey);
    }

    private record Fixture(AgentProperties properties, LexicalSchemaRetriever retriever,
                           ValueRetriever values, HybridSchemaProvider provider) {}
}
