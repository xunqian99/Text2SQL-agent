package com.text2sql.agent.retrieval;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.retrieval.glossary.Glossary;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalDeterminismTest {
    @Test
    void tiedExpansionSeedUsesNameRatherThanFirstMentionAndKeepsProvenance() {
        var catalog = mock(SchemaCatalog.class);
        var loader = mock(GlossaryLoader.class);
        var a = table("alpha");
        var z = table("zeta");
        var al = table("alpha_leaf");
        var zl = table("zeta_leaf");
        when(catalog.full()).thenReturn(new SchemaContext(List.of(a, z, al, zl), List.of(
                new SchemaContext.ForeignKey("alpha", "id", "alpha_leaf", "id"),
                new SchemaContext.ForeignKey("zeta", "id", "zeta_leaf", "id")), "", ""));
        when(catalog.tablesByName()).thenReturn(Map.of("alpha", a, "zeta", z, "alpha_leaf", al, "zeta_leaf", zl));
        when(loader.get()).thenReturn(new Glossary(List.of(), Map.of(
                "alpha", new Glossary.TableEntry(List.of("甲类"), "", Map.of()),
                "zeta", new Glossary.TableEntry(List.of("乙类"), "", Map.of()))));
        var props = new AgentProperties();
        props.getRetrieval().setExpansionSeedLimit(1);
        props.getRetrieval().setTopK(3);
        props.getRetrieval().setBridgeRepairEnabled(false);

        var result = new LexicalSchemaRetriever(loader, catalog, props).retrieve("乙类和甲类");

        assertThat(result.tableNames()).containsExactly("alpha", "zeta", "alpha_leaf");
        assertThat(result.scores()).containsEntry("alpha", 1.0).containsEntry("zeta", 1.0)
                .containsEntry("alpha_leaf", .4);
        assertThat(result.expandedTables()).containsExactly("alpha_leaf");
        assertThat(result.evidence().get("alpha_leaf")).containsExactly("经「alpha」关联");
    }

    @Test
    void sharedAliasesAndEnumHintsIgnoreGlossaryMapOrder() {
        var catalog = mock(SchemaCatalog.class);
        var loader = mock(GlossaryLoader.class);
        var glossary = mock(Glossary.class);
        var a = table("alpha");
        var z = table("zeta");
        when(catalog.full()).thenReturn(new SchemaContext(List.of(a, z), List.of(), "", ""));
        when(catalog.tablesByName()).thenReturn(Map.of("alpha", a, "zeta", z));
        when(loader.get()).thenReturn(glossary);
        when(glossary.relations()).thenReturn(List.of());
        var props = new AgentProperties();
        props.getRetrieval().setRelationHops(0);
        var entry = new Glossary.TableEntry(List.of("实体"), "", Map.of("id",
                new Glossary.ColumnEntry(List.of(), List.of(new Glossary.EnumValue("ok", "正常")))));
        String expectedHints = null;
        for (List<String> order : List.of(List.of("alpha", "zeta"), List.of("zeta", "alpha"))) {
            var map = new LinkedHashMap<String, Glossary.TableEntry>();
            order.forEach(name -> map.put(name, entry));
            when(glossary.tables()).thenReturn(map);
            var result = new LexicalSchemaRetriever(loader, catalog, props).retrieve("正常实体");
            assertThat(result.tableNames()).containsExactly("alpha", "zeta");
            assertThat(result.evidence().keySet()).containsExactly("alpha", "zeta");
            var values = new ValueRetriever(loader);
            String hints = values.renderValueHints(values.findMatches("正常实体", Set.of("alpha", "zeta")));
            if (expectedHints == null) expectedHints = hints;
            assertThat(hints).isEqualTo(expectedHints);
        }
    }

    @Test
    void joinPlanIgnoresTargetAndEdgePermutationWhilePreservingRelevance() {
        var edges = List.of(
                new SchemaContext.ForeignKey("orders", "customer_id", "customers", "id"),
                new SchemaContext.ForeignKey("orders", "seller_id", "sellers", "id"),
                new SchemaContext.ForeignKey("customers", "region_id", "regions", "id"),
                new SchemaContext.ForeignKey("sellers", "region_id", "regions", "id"),
                new SchemaContext.ForeignKey("customers", "member_id", "members", "id"),
                new SchemaContext.ForeignKey("sellers", "member_id", "members", "id"));
        var ranked = List.of("orders", "members", "regions", "sellers", "customers");
        JoinPathPlanner.Plan expected = null;
        for (int seed = 0; seed < 64; seed++) {
            var shuffledEdges = new ArrayList<>(edges);
            var targets = new ArrayList<>(List.of("orders", "members", "regions"));
            Collections.shuffle(shuffledEdges, new Random(seed));
            Collections.shuffle(targets, new Random(seed + 100));
            var schema = new SchemaContext(ranked.stream().map(RetrievalDeterminismTest::table).toList(),
                    shuffledEdges, "", "");
            var graph = JoinGraph.from(schema, Glossary.empty());
            var plan = JoinPathPlanner.planForTargets(graph, new LinkedHashSet<>(targets), ranked);
            if (expected == null) expected = plan;
            assertThat(plan).isEqualTo(expected);
            assertThat(plan.root()).isEqualTo("orders");
            // sellers is more relevant than customers on equal-cost paths.
            assertThat(plan.edges()).anyMatch(e -> e.fromTable().equals("sellers"));
            assertThat(plan.edges()).noneMatch(e -> e.fromTable().equals("customers") || e.toTable().equals("customers"));
        }
    }

    @Test
    void parallelJoinKeysUseFinalTieBreakRatherThanFirstEdge() {
        var a = new JoinGraph.Edge("orders", "a_id", "customers", "id", 1.0);
        var z = new JoinGraph.Edge("orders", "z_id", "customers", "id", 1.0);
        var ranked = List.of("orders", "customers");
        assertThat(JoinPathPlanner.plan(List.of(z, a), ranked))
                .isEqualTo(JoinPathPlanner.plan(List.of(a, z), ranked));
        assertThat(JoinPathPlanner.plan(List.of(z, a), ranked).edges()).containsExactly(a);
    }

    @Test
    void evidenceSnapshotIsOrderedDeeplyImmutableAndKeepsExpandedIdentity() {
        var reasons = new ArrayList<>(List.of("direct"));
        var evidence = new LinkedHashMap<String, List<String>>();
        evidence.put("orders", reasons);
        evidence.put("customers", List.of("expanded"));
        var result = new RetrievalResult(List.of("orders", "customers"), Map.of(), evidence,
                Set.of("customers"), false);
        reasons.add("later mutation");
        evidence.clear();
        assertThat(result.evidence().keySet()).containsExactly("orders", "customers");
        assertThat(result.evidence().get("orders")).containsExactly("direct");
        assertThat(result.expandedTables()).containsExactly("customers");
        assertThatThrownBy(() -> result.evidence().get("orders").add("bad"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static SchemaContext.Table table(String name) {
        return new SchemaContext.Table(name, "", List.of(new SchemaContext.Column("id", "text", false, "")));
    }
}
