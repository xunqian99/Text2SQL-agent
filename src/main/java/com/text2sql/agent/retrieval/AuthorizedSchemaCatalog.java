package com.text2sql.agent.retrieval;

import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;

/**
 * Shared execution/send prerequisite, independent of retrieval and prompt construction.
 * SchemaCatalog is the process snapshot read through the application's DB role, limited
 * to public business base tables by DatabaseSchemaReader. This is not a live DB health
 * check or an online DDL/permission refresh.
 */
@Component
public final class AuthorizedSchemaCatalog {
    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    private final SchemaCatalog catalog;

    public AuthorizedSchemaCatalog(SchemaCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "SchemaCatalog is required");
    }

    public SchemaContext require() {
        return BusinessSchemaUnavailableException.require(() -> {
            SchemaContext schema = catalog.full();
            // Validate identity as well as structure. Never promote a malformed/internal
            // entry to an executable identifier, including the direct INSPECT tools.
            if (schema != null && schema.tables() != null) {
                var names = new HashSet<String>();
                for (var table : schema.tables()) {
                    if (table == null || !DatabaseSchemaReader.isBusinessTableName(table.name())
                            || !names.add(table.name().toLowerCase(Locale.ROOT))) {
                        throw new BusinessSchemaUnavailableException();
                    }
                    if (table.columns() != null) {
                        var columns = new HashSet<String>();
                        for (var column : table.columns()) {
                            if (column == null || !identifier(column.name())
                                    || !columns.add(column.name().toLowerCase(Locale.ROOT))) {
                                throw new BusinessSchemaUnavailableException();
                            }
                        }
                    }
                }
            }
            return schema;
        });
    }

    static boolean identifier(String name) {
        return name != null && IDENTIFIER.matcher(name).matches();
    }
}
