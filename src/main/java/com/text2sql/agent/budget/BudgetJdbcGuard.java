package com.text2sql.agent.budget;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Scope cancellation to the active statement. Never abort a connection already returned to its pool. */
public final class BudgetJdbcGuard {
    private BudgetJdbcGuard() { }

    public static PreparedStatement protect(Connection connection, PreparedStatement statement, int configuredSeconds)
            throws SQLException {
        RequestBudget budget = RequestBudget.current();
        if (budget == null) {
            statement.setQueryTimeout(configuredSeconds);
            return statement;
        }
        Object lock = new Object();
        boolean[] active = {true};
        AutoCloseable registration;
        try {
            int remainingSeconds = Math.max(1, (budget.remainingMillis() + 999) / 1000);
            statement.setQueryTimeout(configuredSeconds <= 0 ? remainingSeconds
                    : Math.min(configuredSeconds, remainingSeconds));
            registration = budget.onCancel(() -> {
                synchronized (lock) {
                    if (!active[0]) return;
                    active[0] = false;
                    // Closing the client socket alone does not immediately interrupt PostgreSQL pg_sleep.
                    try { statement.cancel(); } catch (SQLException ignored) { }
                    finally { try { connection.abort(Runnable::run); } catch (SQLException ignored) { } }
                }
            });
        } catch (SQLException | RuntimeException failure) {
            statement.close();
            throw failure;
        }
        return (PreparedStatement) Proxy.newProxyInstance(BudgetJdbcGuard.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        synchronized (lock) {
                            active[0] = false;
                            registration.close();
                            statement.close();
                            return null;
                        }
                    }
                    try { return method.invoke(statement, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
}
