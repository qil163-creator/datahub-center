package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult.Failure.*;
import static cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult.Status.*;

/** 借用新连接并独占事务；所有原值在写语句中原子比较。当前仅 PostgreSQL 具有真实验证。 */
public final class GuardedTableMutationExecutor {

    /**
     * 返回当前支持。
     */
    public static boolean supports(ProductTypeEnum product) {
        return product == ProductTypeEnum.POSTGRESQL;
    }

    /**
     * 按当前变更定义修改目标对象或数据。
     */
    public GuardedMutationResult mutate(DataSource source, ProductTypeEnum product, GuardedTableMutation mutation) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(mutation);
        if (!supports(product)) {
            return new GuardedMutationResult(NOT_APPLIED, UNSUPPORTED);
        }
        Connection connection;
        try {
            connection = source.getConnection();
        } catch (SQLException | RuntimeException failure) {
            return new GuardedMutationResult(NOT_APPLIED, WRITE_FAILED);
        }
        GuardedMutationResult result;
        try {
            result = execute(connection, product, mutation);
        } catch (RuntimeException failure) {
            result = new GuardedMutationResult(UNKNOWN, WRITE_FAILED);
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException cleanupFailure) {
            return new GuardedMutationResult(result.status(), result.failure(), false);
        }
        return result;
    }

    /**
     * 校验原值和目标条件后应用受保护的数据变更。
     */
    private GuardedMutationResult execute(Connection connection, ProductTypeEnum product,
            GuardedTableMutation mutation) {
        boolean started = false;
        boolean commitAttempted = false;
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            if (!"PostgreSQL".equals(metadata.getDatabaseProductName()) || !metadata.supportsTransactions()
                    || !connection.getAutoCommit() || connection.isReadOnly()
                    || !metadata.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED)
                    || !primaryKeysMatch(metadata, mutation)) {
                return new GuardedMutationResult(NOT_APPLIED, UNSUPPORTED);
            }
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            started = true;
            List<Map<String, Object>> expected = mutation.expectedRows();
            List<Map<String, Object>> rows = mutation.rows();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            for (int i = 0; i < rows.size(); i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new SQLTimeoutException();
                }
                StatementPlan plan = plan(product, mutation, expected.get(i), rows.get(i));
                try (PreparedStatement statement = connection.prepareStatement(plan.sql())) {
                    statement.setQueryTimeout((int) Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L));
                    for (int index = 0; index < plan.parameters().size(); index++) {
                        statement.setObject(index + 1, plan.parameters().get(index));
                    }
                    if (statement.executeUpdate() != 1) {
                        throw new Conflict();
                    }
                }
            }
            commitAttempted = true;
            connection.commit();
            return new GuardedMutationResult(APPLIED, NONE);
        } catch (SQLException | RuntimeException failure) {
            if (commitAttempted) {
                try {
                    connection.rollback();
                } catch (SQLException | RuntimeException ignored) {
                }
                return new GuardedMutationResult(UNKNOWN, WRITE_FAILED);
            }
            if (started) {
                try {
                    connection.rollback();
                } catch (SQLException | RuntimeException rollbackFailure) {
                    return new GuardedMutationResult(UNKNOWN, WRITE_FAILED);
                }
            }
            var cause = failure instanceof Conflict || failure instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("23") ? CONFLICT : WRITE_FAILED;
            return new GuardedMutationResult(NOT_APPLIED, cause);
        }
    }

    /**
     * 检查主键列集合及其顺序是否与预期一致。
     */
    private boolean primaryKeysMatch(DatabaseMetaData metadata, GuardedTableMutation mutation) throws SQLException {
        Set<String> keys = new HashSet<>();
        try (ResultSet rows = metadata.getPrimaryKeys(null, mutation.schema(), mutation.table())) {
            while (rows.next()) {
                if (!mutation.schema().equals(rows.getString("TABLE_SCHEM")) || !mutation.table()
                        .equals(rows.getString("TABLE_NAME"))) {
                    return false;
                }
                keys.add(rows.getString("COLUMN_NAME"));
            }
        }
        return !keys.isEmpty() && keys.equals(new HashSet<>(mutation.primaryKeys()));
    }

    /**
     * 返回计划。
     */
    static StatementPlan plan(ProductTypeEnum product, GuardedTableMutation mutation, Map<String, Object> expected,
            Map<String, Object> row) {
        String table = product.quoteSchemaTableName(mutation.schema(), mutation.table());
        List<Object> values = new ArrayList<>();
        String sql;
        if (mutation.operation() == GuardedTableMutation.Operation.INSERT) {
            mutation.fields().forEach(field -> values.add(row.get(field)));
            sql = "INSERT INTO " + table + " (" + mutation.fields().stream().map(product::quoteName)
                    .collect(Collectors.joining(", "))
                    + ") VALUES (" + String.join(", ", Collections.nCopies(mutation.fields().size(), "?")) + ")";
        } else {
            if (mutation.operation() == GuardedTableMutation.Operation.UPDATE) {
                mutation.fields().forEach(field -> values.add(row.get(field)));
                sql = "UPDATE " + table + " SET " + mutation.fields().stream()
                        .map(field -> product.quoteName(field) + " = ?").collect(Collectors.joining(", "));
            } else {
                sql = "DELETE FROM " + table;
            }
            List<String> predicates = new ArrayList<>();
            List<String> order = new ArrayList<>(mutation.primaryKeys());
            mutation.fields().stream().filter(field -> !mutation.primaryKeys().contains(field)).forEach(order::add);
            for (String field : order) {
                Object value = expected.get(field);
                predicates.add(product.quoteName(field) + (value == null ? " IS NULL" : " = ?"));
                if (value != null) {
                    values.add(value);
                }
            }
            sql += " WHERE " + String.join(" AND ", predicates);
        }
        return new StatementPlan(sql, Collections.unmodifiableList(values));
    }

    record StatementPlan(String sql, List<Object> parameters) {

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "Guarded mutation statement";
        }
    }

    private static final class Conflict extends RuntimeException {

    }
}
