package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.schema.MetadataReadOptions;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaChanges;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaSynchronization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 专用本地 Doris 实例验收；只创建和清理当前测试随机命名的数据库。 */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/.*")
class DorisSchemaSynchronizationDorisTest {

    @Test void createThenAlterPreservesRowsPrecisionAndNativeLayout() throws Exception {
        String schema = "model_it_" + UUID.randomUUID().toString().replace("-", "");
        String user = System.getenv().getOrDefault("DATAHUB_TEST_DORIS_USER", "root");
        String password = System.getenv().getOrDefault("DATAHUB_TEST_DORIS_PASSWORD", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"), user, password)) {
            sql(connection, "CREATE DATABASE " + DorisNativeSchema.quote(schema));
            try {
                var database = new DatabaseDorisImpl();
                var id = column("id", "bigint", null, null, null, true);
                var note = column("note", "varchar", 20, null, null, false);
                var amount = column("amount", "decimal", null, 38, 18, false);
                var fields = List.of(id, note, amount);
                var options = new MetadataReadOptions(MetadataReadOptions.Freshness.REQUIRE_FRESH, 60);
                var before = SchemaChanges.observe(connection, database, schema, "orders", options);
                var create = SchemaSynchronization.createPlan(database, schema, "orders", "订单", fields);
                assertTrue(create.ready(), create.diagnostics().toString());
                var result = SchemaChanges.execute(connection, database,
                        SchemaChanges.plan(database, schema, "orders", create.statements()), before.precondition(), options,
                        ignored -> { }, observed -> {
                            var remaining = SchemaSynchronization.plan(connection, database, schema, "orders", "订单", fields, options);
                            return remaining.ready() && remaining.statements().isEmpty();
                        });
                assertEquals(SchemaChanges.Outcome.COMMITTED, result.outcome());
                String relation = DorisNativeSchema.relation(schema, "orders");
                String supplementaryText = "😀".repeat(20);
                sql(connection, "INSERT INTO " + relation + " VALUES(9007199254740993,'" + supplementaryText + "',12345678901234567890.123456789012345678)");
                var layout = DorisJdbcSupport.inspect(connection, schema, "orders", 30).table();
                var wider = column("note", "varchar", 50, null, null, false);
                var added = column("active", "int", null, null, null, false);
                added.setNullable(false);
                added.setDefaultValue("1");
                var desired = List.of(id, wider, amount, added);
                var modify = SchemaSynchronization.plan(connection, database, schema, "orders", "订单更新", desired, options);
                assertTrue(modify.ready(), modify.diagnostics().toString());
                before = SchemaChanges.observe(connection, database, schema, "orders", options);
                result = SchemaChanges.execute(connection, database,
                        SchemaChanges.plan(database, schema, "orders", modify.statements()), before.precondition(), options,
                        ignored -> { }, observed -> {
                            var remaining = SchemaSynchronization.plan(connection, database, schema, "orders", "订单更新", desired, options);
                            return remaining.ready() && remaining.statements().isEmpty();
                        });
                assertEquals(SchemaChanges.Outcome.COMMITTED, result.outcome());
                var after = DorisJdbcSupport.inspect(connection, schema, "orders", 30).table();
                assertEquals(layout.model(), after.model());
                assertEquals(layout.keys(), after.keys());
                assertEquals(layout.distribution(), after.distribution());
                assertEquals(layout.buckets(), after.buckets());
                var invalidDefault = added.copy();
                invalidDefault.setDefaultValue("2");
                var refused = SchemaSynchronization.plan(connection, database, schema, "orders", "订单更新",
                        List.of(id, wider, amount, invalidDefault), options);
                assertFalse(refused.ready());
                assertTrue(refused.statements().isEmpty());
                // 原生 4.0 允许 NOT NULL 放宽为 NULL；验证保留原默认值及已有数据。
                var optional = added.copy();
                optional.setNullable(true);
                var relaxed = List.of(id, wider, amount, optional);
                var relaxPlan = SchemaSynchronization.plan(connection, database, schema, "orders", "订单更新", relaxed, options);
                assertTrue(relaxPlan.ready(), relaxPlan.diagnostics().toString());
                before = SchemaChanges.observe(connection, database, schema, "orders", options);
                result = SchemaChanges.execute(connection, database,
                        SchemaChanges.plan(database, schema, "orders", relaxPlan.statements()), before.precondition(), options,
                        ignored -> { }, observed -> {
                            var remaining = SchemaSynchronization.plan(connection, database, schema, "orders", "订单更新", relaxed, options);
                            return remaining.ready() && remaining.statements().isEmpty();
                        });
                assertEquals(SchemaChanges.Outcome.COMMITTED, result.outcome());
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,note,amount,active FROM " + relation)) {
                    assertTrue(rows.next());
                    assertEquals(9007199254740993L, rows.getLong(1));
                    assertEquals(supplementaryText, rows.getString(2));
                    assertEquals(new BigDecimal("12345678901234567890.123456789012345678"), rows.getBigDecimal(3));
                    assertEquals(1, rows.getInt(4));
                    assertFalse(rows.next());
                }
            } finally {
                sql(connection, "DROP DATABASE " + DorisNativeSchema.quote(schema) + " FORCE");
            }
        }
    }

    private static ColumnDescription column(String name, String type, Integer length, Integer precision, Integer scale, boolean primary) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        SqlTypeSemantics.applyDeclaredType(column, type, length, precision, scale);
        column.setPk(primary);
        column.setNullable(!primary);
        return column;
    }

    private static void sql(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            statement.execute(sql);
        }
    }
}
