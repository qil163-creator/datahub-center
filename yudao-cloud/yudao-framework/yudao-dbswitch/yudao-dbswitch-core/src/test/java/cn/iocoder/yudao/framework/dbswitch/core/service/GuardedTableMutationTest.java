package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import static cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult.Status.*;
import static cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult.Failure.*;
import static org.junit.jupiter.api.Assertions.*;

class GuardedTableMutationTest {
    @Test
    void fullOriginalValuesAreBoundAndNullUsesIsNull() {
        Map<String, Object> old = row(1, null); Map<String, Object> next = row(1, "new' OR true --");
        var mutation = mutation(List.of(old), List.of(next));
        var plan = GuardedTableMutationExecutor.plan(ProductTypeEnum.POSTGRESQL, mutation, old, next);
        assertEquals("UPDATE \"fixture\".\"rows\" SET \"id\" = ?, \"value\" = ? WHERE \"id\" = ? AND \"value\" IS NULL", plan.sql());
        assertEquals(Arrays.asList(1L, "new' OR true --", 1L), plan.parameters());
        assertFalse(plan.toString().contains("OR true"));
    }
    @Test
    void payloadIsDefensivelyCopiedAndInvalidIdentifiersAreRejected() {
        java.sql.Timestamp time = java.sql.Timestamp.valueOf("2026-10-12 12:00:00");
        Map<String, Object> old = row(1, time); Map<String, Object> next = row(1, time);
        var mutation = mutation(List.of(old), List.of(next));
        time.setTime(0); old.put("value", "changed");
        assertNotEquals(0, ((Timestamp) mutation.expectedRows().getFirst().get("value")).getTime());
        ((Timestamp) mutation.rows().getFirst().get("value")).setTime(0);
        assertNotEquals(0, ((Timestamp) mutation.rows().getFirst().get("value")).getTime());
        assertThrows(UnsupportedOperationException.class, () -> mutation.rows().getFirst().put("value", "changed"));
        assertFalse(mutation.toString().contains("2026"));
        for (String table : List.of("bad\";DROP TABLE x;--", "x\n", "x".repeat(64))) {
            assertThrows(IllegalArgumentException.class, () -> new GuardedTableMutation("fixture", table, List.of("id", "value"),
                    List.of("id"), GuardedTableMutation.Operation.UPDATE, List.of(row(1, "old")), List.of(row(1, "new"))));
        }
        assertThrows(IllegalArgumentException.class, () -> mutation(List.of(row(1, "old")), List.of(row(2, "new"))));
        assertThrows(IllegalArgumentException.class, () -> mutation(List.of(row(1, "old"), row(1, "old")), List.of(row(1, "new"), row(1, "new"))));
    }
    @Test
    void wholeBatchRollsBackOnConflict() {
        Jdbc fixture = new Jdbc(); fixture.counts = List.of(1, 0);
        var result = execute(fixture, mutation(List.of(row(1, "old"), row(2, "old")), List.of(row(1, "new"), row(2, "new"))));
        assertEquals(NOT_APPLIED, result.status()); assertEquals(CONFLICT, result.failure());
        assertEquals(1, fixture.rollbacks); assertEquals(0, fixture.commits); assertEquals(1, fixture.closes);
        assertEquals(30, fixture.timeout);
    }
    @Test
    void commitUncertaintyStaysUnknownEvenWhenRollbackReturnsNormally() {
        Jdbc fixture = new Jdbc(); fixture.commitFailure = true;
        var result = execute(fixture, mutation(List.of(row(1, "old")), List.of(row(1, "new"))));
        assertEquals(UNKNOWN, result.status()); assertEquals(WRITE_FAILED, result.failure()); assertEquals(1, fixture.rollbacks);
        assertFalse(result.toString().contains("sensitive"));
    }
    @Test
    void rollbackUncertaintyCannotBecomeNotApplied() {
        Jdbc fixture = new Jdbc(); fixture.counts = List.of(0); fixture.rollbackFailure = true;
        assertEquals(UNKNOWN, execute(fixture, mutation(List.of(row(1, "old")), List.of(row(1, "new")))).status());
    }
    @Test
    void cleanupFailureDoesNotReverseConfirmedCommit() {
        Jdbc fixture = new Jdbc(); fixture.closeFailure = true;
        var result = execute(fixture, mutation(List.of(row(1, "old")), List.of(row(1, "new"))));
        assertEquals(APPLIED, result.status()); assertEquals(NONE, result.failure()); assertFalse(result.cleanupConfirmed());
        assertEquals(0, fixture.rollbacks);
    }
    @Test
    void unsupportedProductAndUnverifiedConnectionAreRejectedBeforeWriting() {
        Jdbc fixture = new Jdbc(); var mutation = mutation(List.of(row(1, "old")), List.of(row(1, "new")));
        assertEquals(UNSUPPORTED, new GuardedTableMutationExecutor().mutate(fixture.source(), ProductTypeEnum.HIVE, mutation).failure());
        assertEquals(0, fixture.opens);
        fixture.transactions = false;
        assertEquals(UNSUPPORTED, execute(fixture, mutation).failure()); assertEquals(0, fixture.prepares);
        fixture.transactions = true; fixture.autoCommit = false;
        assertEquals(UNSUPPORTED, execute(fixture, mutation).failure()); assertEquals(0, fixture.prepares);
        fixture.autoCommit = true; fixture.key = "other";
        assertEquals(UNSUPPORTED, execute(fixture, mutation).failure()); assertEquals(0, fixture.prepares);
    }
    @Test
    void verifiedWriteErrorWithRollbackHasKnownNotAppliedOutcome() {
        Jdbc fixture = new Jdbc(); fixture.writeFailure = true;
        var result = execute(fixture, mutation(List.of(row(1, "old")), List.of(row(1, "new"))));
        assertEquals(NOT_APPLIED, result.status()); assertEquals(WRITE_FAILED, result.failure()); assertEquals(1, fixture.rollbacks);
    }
    private static GuardedMutationResult execute(Jdbc fixture, GuardedTableMutation mutation) {
        return new GuardedTableMutationExecutor().mutate(fixture.source(), ProductTypeEnum.POSTGRESQL, mutation);
    }
    private static GuardedTableMutation mutation(List<Map<String, Object>> old, List<Map<String, Object>> next) {
        return new GuardedTableMutation("fixture", "rows", List.of("id", "value"), List.of("id"), GuardedTableMutation.Operation.UPDATE, old, next);
    }
    static Map<String, Object> row(long id, Object value) { Map<String, Object> row = new LinkedHashMap<>(); row.put("id", id); row.put("value", value); return row; }

    private static final class Jdbc {
        int opens, prepares, commits, rollbacks, closes, timeout;
        boolean transactions = true, autoCommit = true, commitFailure, rollbackFailure, closeFailure, writeFailure;
        String key = "id";
        List<Integer> counts = List.of(1);
        DataSource source() {
            return proxy(DataSource.class, (name, args) -> {
                if (name.equals("getConnection")) { opens++; return connection(); }
                return null;
            });
        }
        Connection connection() {
            return proxy(Connection.class, (name, args) -> {
                switch (name) {
                    case "getMetaData": return metadata();
                    case "getAutoCommit": return autoCommit;
                    case "isReadOnly": return false;
                    case "prepareStatement": prepares++; return statement();
                    case "commit": commits++; if (commitFailure) throw new SqlFailure(); return null;
                    case "rollback": rollbacks++; if (rollbackFailure) throw new SqlFailure(); return null;
                    case "close": closes++; if (closeFailure) throw new SqlFailure(); return null;
                    default: return null;
                }
            });
        }
        DatabaseMetaData metadata() {
            return proxy(DatabaseMetaData.class, (name, args) -> switch (name) {
                case "getDatabaseProductName" -> "PostgreSQL";
                case "supportsTransactions", "supportsTransactionIsolationLevel" -> transactions;
                case "getPrimaryKeys" -> keys();
                default -> null;
            });
        }
        ResultSet keys() {
            AtomicInteger cursor = new AtomicInteger();
            return proxy(ResultSet.class, (name, args) -> switch (name) {
                case "next" -> cursor.getAndIncrement() == 0;
                case "getString" -> switch ((String) args[0]) { case "TABLE_SCHEM" -> "fixture"; case "TABLE_NAME" -> "rows"; default -> key; };
                default -> null;
            });
        }
        PreparedStatement statement() {
            return proxy(PreparedStatement.class, (name, args) -> {
                if (name.equals("setQueryTimeout")) timeout = (Integer) args[0];
                if (name.equals("executeUpdate")) { if (writeFailure) throw new SqlFailure(); return counts.get(Math.min(prepares - 1, counts.size() - 1)); }
                return null;
            });
        }
    }
    private static final class SqlFailure extends RuntimeException { }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> body) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> body.apply(method.getName(), args));
    }
}
