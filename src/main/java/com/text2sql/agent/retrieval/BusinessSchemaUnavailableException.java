package com.text2sql.agent.retrieval;

import com.text2sql.agent.budget.RequestBudget;
import java.util.function.Supplier;

/** A catalog failure is an environment failure, never model repair feedback. */
public final class BusinessSchemaUnavailableException extends RuntimeException {
    public BusinessSchemaUnavailableException() {
        super("完整业务 Schema 不可用，未执行 SQL");
    }

    public static SchemaContext require(Supplier<SchemaContext> source) {
        try {
            SchemaContext schema = source.get();
            if (schema == null || schema.tables() == null || schema.tables().isEmpty()
                    || schema.tables().stream().anyMatch(t -> t == null || t.name() == null
                    || t.name().isBlank() || t.columns() == null || t.columns().isEmpty()
                    || t.columns().stream().anyMatch(c -> c == null || c.name() == null || c.name().isBlank()))) {
                throw new BusinessSchemaUnavailableException();
            }
            return schema;
        } catch (RequestBudget.Exceeded exceeded) {
            throw exceeded;
        } catch (RuntimeException unavailable) {
            // Do not leak connection details or turn catalog faults into SQL correction feedback.
            throw new BusinessSchemaUnavailableException();
        }
    }
}
