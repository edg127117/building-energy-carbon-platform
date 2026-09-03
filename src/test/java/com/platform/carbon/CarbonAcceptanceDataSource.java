package com.platform.carbon;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.lang.reflect.*;
import java.sql.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** 只包裹测试 JDBC 句柄计时，不改 SQL、事务边界、连接池上限或生产查询超时。 */
final class CarbonAcceptanceDataSource extends AbstractDataSource implements AutoCloseable {
    final HikariDataSource pool;
    private final LongAdder queries = new LongAdder();
    private final LongAdder queryNanos = new LongAdder();
    private final AtomicLong maxQueryNanos = new AtomicLong();
    private final AtomicLong maxBorrowNanos = new AtomicLong();

    CarbonAcceptanceDataSource(HikariDataSource pool) { this.pool = pool; }

    @Override public Connection getConnection() throws SQLException {
        long started = System.nanoTime();
        try { return connection(pool.getConnection()); }
        finally { maxBorrowNanos.accumulateAndGet(System.nanoTime() - started, Math::max); }
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("Acceptance uses the configured isolated account only");
    }

    private Connection connection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object value = invoke(delegate, method, args);
                    if (value instanceof Statement statement) {
                        Class<?> type = value instanceof CallableStatement ? CallableStatement.class
                                : value instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type},
                                (p, operation, parameters) -> {
                                    if (!operation.getName().startsWith("execute")) {
                                        return invoke(statement, operation, parameters);
                                    }
                                    long started = System.nanoTime();
                                    try { return invoke(statement, operation, parameters); }
                                    finally {
                                        long elapsed = System.nanoTime() - started;
                                        queries.increment(); queryNanos.add(elapsed);
                                        maxQueryNanos.accumulateAndGet(elapsed, Math::max);
                                    }
                                });
                    }
                    return value;
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    Map<String, Object> metrics() {
        return Map.of("queryCount", queries.sum(), "queryTotalMs", queryNanos.sum() / 1_000_000.0,
                "queryMaxMs", maxQueryNanos.get() / 1_000_000.0,
                "connectionBorrowMaxMs", maxBorrowNanos.get() / 1_000_000.0);
    }

    void reset() { queries.reset(); queryNanos.reset(); maxQueryNanos.set(0); maxBorrowNanos.set(0); }
    @Override public void close() { pool.close(); }
}
