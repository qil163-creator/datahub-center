package cn.iocoder.yudao.framework.dbswitch.product.doris;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 冻结建表验收与异步结束证明，避免字段相同或历史最新任务掩盖真实差异。 */
class DorisModelingSchemaScriptTest {
    private static Table expected() {
        return new Table(List.of(new Column("id", "bigint", false, null, "主键"),
                new Column("note", "varchar(80)", true, null, "备注")), "UNIQUE", List.of("id"),
                List.of("id"), 10, Map.of("replication_num", "1", "enable_unique_key_merge_on_write", "true"), "订单");
    }

    @Test void equivalentServerPropertiesAndNullableDefaultAreAccepted() {
        var expected = expected();
        var actual = new Table(List.of(expected.columns().getFirst(),
                new Column("note", "varchar(80)", true, "NULL", "备注")), expected.model(), expected.keys(),
                expected.distribution(), expected.buckets(), Map.of("replication_allocation", "tag.location.default: 1",
                "enable_unique_key_merge_on_write", "true", "storage_format", "V2"), expected.comment());
        assertTrue(matches(expected, actual));
    }

    @Test void sameColumnsDoNotHideDifferentKeyModelBucketsReplicasOrMergeSemantics() {
        var expected = expected();
        assertFalse(matches(expected, copy(expected, "DUPLICATE", 10, expected.properties())));
        assertFalse(matches(expected, copy(expected, "UNIQUE", 3, expected.properties())));
        assertFalse(matches(expected, copy(expected, "UNIQUE", 10, Map.of("replication_num", "3",
                "enable_unique_key_merge_on_write", "true"))));
        assertFalse(matches(expected, copy(expected, "UNIQUE", 10, Map.of("replication_num", "1",
                "enable_unique_key_merge_on_write", "false"))));
        assertFalse(matches(expected, copy(expected, "UNIQUE", 10, Map.of("replication_num", "1",
                "enable_unique_key_merge_on_write", "true", "function_column.sequence_col", "id"))));
        assertFalse(matches(expected, copy(expected, "UNIQUE", 10, Map.of("replication_num", "1",
                "enable_unique_key_merge_on_write", "true", "function_column.sequence_type", "BIGINT"))));
    }

    @Test void automaticBucketCountRequiresNativeAutoEvidence() {
        var automatic = copy(expected(), "UNIQUE", 0, expected().properties());
        assertTrue(matches(automatic, automatic));
        assertTrue(matches(automatic, copy(automatic, "UNIQUE", 8, Map.of("replication_allocation", "tag.location.default:1",
                "enable_unique_key_merge_on_write", "true", "auto_bucket", "true"))));
        assertFalse(matches(automatic, expected()));
        assertFalse(matches(expected(), automatic));
    }

    @Test void createComparisonRequiresTheFrozenBoundSingleCreate() {
        String ddl = expected().ddl("dw", "orders");
        assertThrows(IllegalArgumentException.class, () -> DorisModelingSchemaScript.matchesCreate("dw", "other", ddl, expected()));
        assertThrows(IllegalArgumentException.class, () -> DorisModelingSchemaScript.matchesCreate("dw", "orders", ddl + ";DROP TABLE secret", expected()));
        assertThrows(IllegalArgumentException.class, () -> DorisModelingSchemaScript.matchesCreate("dw", "orders", "ALTER TABLE `dw`.`orders` DROP COLUMN `note`", expected()));
    }

    @Test void activityIsQueriedDirectlyInsteadOfTrustingTheLatestHistoricalJob() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        var queries = new ArrayList<String>();
        var runningReads = new AtomicInteger();
        when(statement.executeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0); queries.add(sql);
            var rows = mock(ResultSet.class);
            if (sql.contains("State = 'RUNNING'") && runningReads.getAndIncrement() == 0) {
                when(rows.next()).thenReturn(true, false);
                when(rows.getString("TableName")).thenReturn("orders");
                when(rows.getString("State")).thenReturn("RUNNING");
            } else if (sql.contains("CreateTime")) {
                // 旧算法会看见同秒的已完成历史行，错误地提前返回。
                when(rows.next()).thenReturn(true, false);
                when(rows.getString("TableName")).thenReturn("orders");
                when(rows.getString("State")).thenReturn("FINISHED");
            }
            return rows;
        });

        DorisModelingSchemaScript.await(connection, "dw", "orders", 2);

        assertEquals(2, runningReads.get());
        assertEquals(6, queries.size());
        assertTrue(queries.stream().noneMatch(sql -> sql.contains("CreateTime")));
        assertTrue(queries.stream().allMatch(sql -> sql.contains("TableName = 'orders' AND State = ")));
    }

    @Test void activeJobForAnotherTargetCannotConfirmCompletion() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString("TableName")).thenReturn("other");
        when(rows.getString("State")).thenReturn("PENDING");
        assertThrows(SQLException.class, () -> DorisModelingSchemaScript.await(connection, "dw", "orders", 1));
    }

    private static boolean matches(Table expected, Table actual) {
        return DorisModelingSchemaScript.matchesCreate("dw", "orders", expected.ddl("dw", "orders"), actual);
    }

    private static Table copy(Table table, String model, int buckets, Map<String, String> properties) {
        return new Table(table.columns(), model, table.keys(), table.distribution(), buckets, properties, table.comment());
    }
}
