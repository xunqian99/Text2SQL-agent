package com.text2sql.agent.semantic;

import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.retrieval.glossary.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CountComparisonContractTest {
    private static final List<ValueRetriever.ValueEntry> ENTRIES = List.of(
            new ValueRetriever.ValueEntry("已送达", "orders", "order_status", "delivered"),
            new ValueRetriever.ValueEntry("已取消", "orders", "order_status", "canceled"));
    private static final List<SchemaContext.Table> TABLES = List.of(table("orders", "order_status"));

    @Test void contractTracksSourceOffsetsAndOriginalColumnOrderRatherThanIndexOrder() {
        String q = "分别统计已取消订单数和已送达订单数";
        var contract = CountComparisonContract.resolve(q, ENTRIES, TABLES);
        assertThat(contract.decision()).isEqualTo(CountComparisonContract.Decision.MATCHED);
        assertThat(contract.bindings()).extracting(CountComparisonContract.Binding::value).containsExactly("canceled", "delivered");
        assertThat(contract.bindings()).allSatisfy(b -> {
            assertThat(q.substring(b.start(),b.end())).isEqualTo(b.matchedWord());
            assertThat(b.table()).isEqualTo("orders");
            assertThat(b.column()).isEqualTo("order_status");
            assertThat(b.source()).isEqualTo(CountComparisonContract.Source.GLOSSARY_LABEL);
        });
        assertThat(contract.render()).contains("一行、恰好2个计数列", "第1列：COUNT(*) FILTER (WHERE orders.order_status = 'canceled')",
                "空子集/空输入计0", "不要返回枚举值列或GROUP BY枚举字段");
        assertThatThrownBy(() -> contract.bindings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void physicalValueUsesTokenBoundariesAndPreservesActualSpellingAsEvidence() {
        var contract = CountComparisonContract.resolve("BOLETO 和信用卡各占多少笔？", List.of(
                new ValueRetriever.ValueEntry("信用卡", "payments", "type", "credit_card"),
                new ValueRetriever.ValueEntry("银行票据", "payments", "type", "boleto")), List.of(table("payments", "type")));
        assertThat(contract.decision()).isEqualTo(CountComparisonContract.Decision.MATCHED);
        assertThat(contract.bindings().getFirst().source()).isEqualTo(CountComparisonContract.Source.PHYSICAL_VALUE);
        assertThat(contract.bindings().getFirst().matchedWord()).isEqualTo("BOLETO");
        assertThat(contract.bindings()).extracting(CountComparisonContract.Binding::value).containsExactly("boleto", "credit_card");
        assertThat(CountComparisonContract.resolve("superboleto和信用卡各多少笔", List.of(
                new ValueRetriever.ValueEntry("信用卡", "payments", "type", "credit_card"),
                new ValueRetriever.ValueEntry("银行票据", "payments", "type", "boleto")), List.of(table("payments", "type"))).decision())
                .isEqualTo(CountComparisonContract.Decision.INSUFFICIENT_VALUES);
    }

    @ParameterizedTest @ValueSource(strings={"已送达和已取消订单各多少笔，请用纵表", "每种状态中已送达和已取消各多少笔",
            "已送达和已取消订单各多少笔，返回两列", "按州统计已送达和已取消订单数量", "按承运商，已送达和已取消订单各多少笔"})
    void explicitOrGroupedLayoutIsNeverOverridden(String question) {
        var result=CountComparisonContract.resolve(question,ENTRIES,TABLES);
        assertThat(result.decision()).isEqualTo(CountComparisonContract.Decision.EXPLICIT_OR_GROUP_LAYOUT);
        assertThat(result.render()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"2018年已送达和已取消各多少笔", "已送达和已取消各多少笔，平均金额多少",
            "已送达和已取消各多少笔，占比是多少", "已送达和已取消各多少笔，不要计入其他记录",
            "不是已送达和已取消的各有多少笔", "已送达和已取消相差多少笔", "2017已送达和2018已取消订单各多少笔"})
    void unsupportedCompositionAbstainsInsteadOfChangingTheQuestion(String question) {
        assertThat(CountComparisonContract.resolve(question,ENTRIES,TABLES).decision())
                .isEqualTo(CountComparisonContract.Decision.UNSUPPORTED_COMPOSITION);
    }

    @ParameterizedTest @ValueSource(strings={"已送达和已取消订单的复购率", "已送达订单有多少笔", "已送达和已取消订单", ""})
    void noMatchingCountEnumerationDoesNotInventLayout(String question) {
        assertThat(CountComparisonContract.resolve(question,ENTRIES,TABLES).decision())
                .isEqualTo(CountComparisonContract.Decision.NO_COUNT_INTENT);
    }

    @Test void missingPrunedFieldAndMissingTableDoNotProduceAnUnusableContract() {
        assertThat(CountComparisonContract.resolve("已送达和已取消各多少笔",ENTRIES,List.of(table("orders","order_id"))).decision())
                .isEqualTo(CountComparisonContract.Decision.INSUFFICIENT_VALUES);
        assertThat(CountComparisonContract.resolve("已送达和已取消各多少笔",ENTRIES,List.of()).render()).isEmpty();
    }

    @Test void multipleFieldsRemainAmbiguousRatherThanChoosingByRetrievalWeight() {
        var entries=new ArrayList<>(ENTRIES);
        entries.add(new ValueRetriever.ValueEntry("已送达","shipments","status","delivered"));
        entries.add(new ValueRetriever.ValueEntry("已取消","shipments","status","canceled"));
        var contract=CountComparisonContract.resolve("已送达和已取消各多少笔",entries,List.of(table("orders","order_status"),table("shipments","status")));
        assertThat(contract.decision()).isEqualTo(CountComparisonContract.Decision.AMBIGUOUS_FIELDS);
        assertThat(contract.bindings()).hasSize(4);
        assertThat(contract.render()).isEmpty();
    }

    @Test void duplicatedAliasSameValueDeduplicatesButConflictingValuesAbstain() {
        var entries=new ArrayList<>(ENTRIES); entries.add(ENTRIES.getFirst());
        assertThat(CountComparisonContract.resolve("已送达和已取消各多少笔",entries,TABLES).bindings()).hasSize(2);
        entries.add(new ValueRetriever.ValueEntry("已取消","orders","order_status","unavailable"));
        assertThat(CountComparisonContract.resolve("已送达和已取消各多少笔",entries,TABLES).decision())
                .isEqualTo(CountComparisonContract.Decision.AMBIGUOUS_VALUE_BINDING);
    }

    @Test void excessiveValuesAreRejectedWithoutTruncatingAwayAnyEvidence() {
        var entries=new ArrayList<ValueRetriever.ValueEntry>();
        for(int i=0;i<9;i++)entries.add(new ValueRetriever.ValueEntry("标签"+i,"orders","order_status","v"+i));
        var contract=CountComparisonContract.resolve("标签0、标签1、标签2、标签3、标签4、标签5、标签6、标签7、标签8各多少笔",entries,TABLES);
        assertThat(contract.decision()).isEqualTo(CountComparisonContract.Decision.TOO_MANY_VALUES);
        assertThat(contract.bindings()).hasSize(9);
        assertThat(contract.render()).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void fullHybridAndFallbackAppendContractWithoutChangingLegacyValueAlignment(boolean fallback) {
        var glossary=mock(GlossaryLoader.class);
        when(glossary.get()).thenReturn(new Glossary(List.of(),Map.of("orders",new Glossary.TableEntry(List.of(),"",Map.of("order_status",
                new Glossary.ColumnEntry(List.of(),List.of(new Glossary.EnumValue("delivered","已送达"),new Glossary.EnumValue("canceled","已取消"))))))));
        var values=new ValueRetriever(glossary);
        var catalog=mock(SchemaCatalog.class);
        when(catalog.full()).thenReturn(new SchemaContext(TABLES,List.of(),"","CREATE TABLE orders (...)") );
        var retriever=mock(LexicalSchemaRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(new RetrievalResult(List.of("orders"),Map.of("orders",1.0),Map.of(),Set.of(),fallback));
        var props=new com.text2sql.agent.config.AgentProperties();
        props.getSemantic().setEnabled(false); props.getRetrieval().setJoinHintsEnabled(false);
        var providers=List.<SchemaProvider>of(new FullSchemaProvider(catalog,null,null,props,values),
                new HybridSchemaProvider(catalog,retriever,glossary,props,null,null,values,null));
        String q="已送达和已取消订单各多少笔";
        String legacy=values.renderValueHints(values.findMatches(q,Set.of("orders")));
        for(var provider:providers) {
            assertThat(provider.provide(q).valueHints()).startsWith(legacy).contains("REQUEST_RESULT_CONTRACT named_filter_counts_v1");
            String explicit=q+"，请用纵表";
            assertThat(provider.provide(explicit).valueHints()).isEqualTo(values.renderValueHints(values.findMatches(explicit,Set.of("orders"))));
        }
    }

    private static SchemaContext.Table table(String name,String column) {
        return new SchemaContext.Table(name,"",List.of(new SchemaContext.Column(column,"text",false,"")));
    }
}
