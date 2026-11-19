package com.text2sql.agent.retrieval;

import java.util.List;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;

/** Explicit test-only metadata sources; no optional production catalog or prompt fallback. */
public final class CatalogFixture {
    private CatalogFixture() { }

    public static AuthorizedSchemaCatalog from(Supplier<SchemaContext> source) {
        var catalog = mock(SchemaCatalog.class);
        when(catalog.full()).thenAnswer(i -> source.get());
        return new AuthorizedSchemaCatalog(catalog);
    }

    public static AuthorizedSchemaCatalog healthy() {
        return from(() -> new SchemaContext(List.of(new SchemaContext.Table("orders", null,
                List.of(new SchemaContext.Column("order_id", "text", false, null)))), List.of(), "", "fixture"));
    }
}
