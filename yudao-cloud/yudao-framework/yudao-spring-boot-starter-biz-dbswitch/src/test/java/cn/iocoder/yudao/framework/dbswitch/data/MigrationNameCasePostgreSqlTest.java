package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.type.CaseConvertEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings;
import cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL 建表与写入：物理表名/字段名/主键必须与预览共用的命名结果一致。 */
@Timeout(120)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationNameCasePostgreSqlTest {
    private final String sourceSchema = "case_src_" + UUID.randomUUID().toString().replace("-", "");
    private final String targetSchema = "case_dst_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach void setup() throws Exception {
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + sourceSchema);
            statement.execute("CREATE SCHEMA " + targetSchema);
            statement.execute("CREATE TABLE " + sourceSchema + ".\"OrderItem\"(\"orderId\" int PRIMARY KEY,"
                    + " \"createdAt\" timestamp NOT NULL, \"customer_Name\" varchar(40))");
            statement.execute("INSERT INTO " + sourceSchema + ".\"OrderItem\" VALUES"
                    + "(1, TIMESTAMP '2026-10-08 10:00:00', 'Alice'), (2, TIMESTAMP '2026-10-08 11:00:00', NULL)");
            statement.execute("CREATE TABLE " + sourceSchema + ".order_line(line_id int PRIMARY KEY, unit_price numeric(10,2))");
            statement.execute("INSERT INTO " + sourceSchema + ".order_line VALUES (7, 1.50)");
        }
    }

    @AfterEach void cleanup() throws Exception {
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + sourceSchema + " CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS " + targetSchema + " CASCADE");
        }
    }

    @Test void snakeModesCreateAndFillTheTargetThatThePreviewHelpersReport() throws Exception {
        var request = request("OrderItem");
        request.getTarget().setTableNameCase(CaseConvertEnum.SNAKE);
        request.getTarget().setColumnNameCase(CaseConvertEnum.SNAKE);
        String table = MigrationTableNames.resolve(request.getSource().getFirst(), request.getTarget(), "OrderItem");
        var plan = MigrationColumnMappings.resolve(request.getSource().getFirst(), request.getTarget(), "OrderItem",
                List.of(column("orderId"), column("createdAt"), column("customer_Name")), List.of("orderId"));
        assertEquals("order_item", table);
        assertEquals(List.of("order_id", "created_at", "customer_name"), names(plan.columns()));
        run(request);
        assertEquals(names(plan.columns()), columns(table));
        assertEquals(plan.primaryKeys(), primaryKey(table));
        assertEquals(List.of("1|Alice", "2|null"), rows("SELECT order_id, customer_name FROM " + targetSchema + ".order_item ORDER BY 1"));
    }

    @Test void tableAndColumnModesAreAppliedIndependently() throws Exception {
        var request = request("order_line");
        request.getTarget().setTableNameCase(CaseConvertEnum.UPPER);
        request.getTarget().setColumnNameCase(CaseConvertEnum.CAMEL);
        request.getTarget().setLowercase(true);
        run(request);
        assertEquals(List.of("lineId", "unitPrice"), columns("ORDER_LINE"));
        assertEquals(List.of("lineId"), primaryKey("ORDER_LINE"));
        assertEquals(List.of("7|1.50"), rows("SELECT \"lineId\", \"unitPrice\" FROM " + targetSchema + ".\"ORDER_LINE\""));
    }

    private List<String> columns(String table) throws Exception {
        return rows("SELECT column_name FROM information_schema.columns WHERE table_schema='" + targetSchema
                + "' AND table_name='" + table + "' ORDER BY ordinal_position");
    }

    private List<String> primaryKey(String table) throws Exception {
        return rows("SELECT k.column_name FROM information_schema.table_constraints t JOIN information_schema.key_column_usage k"
                + " ON k.constraint_schema=t.constraint_schema AND k.constraint_name=t.constraint_name"
                + " WHERE t.table_schema='" + targetSchema + "' AND t.table_name='" + table + "' AND t.constraint_type='PRIMARY KEY'"
                + " ORDER BY k.ordinal_position");
    }

    private static List<String> rows(String sql) throws Exception {
        var result = new ArrayList<String>();
        try (var connection = open(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            int width = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                var row = new StringBuilder(String.valueOf(rows.getString(1)));
                for (int i = 2; i <= width; i++) row.append('|').append(rows.getString(i));
                result.add(row.toString());
            }
        }
        return result;
    }

    private MigrationRequest request(String table) {
        var request = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        source.setUrl(System.getProperty("datahub.test.postgres.url"));
        source.setUsername(user());
        source.setPassword(password());
        source.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        source.setSourceSchema(sourceSchema);
        source.setIncludeOrExclude(1);
        source.setSourceTableNames(List.of(table));
        source.setPoolSize(1);
        source.setFetchSize(5);
        request.getSource().add(source);
        var target = request.getTarget();
        target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        target.setUrl(System.getProperty("datahub.test.postgres.url"));
        target.setUsername(user());
        target.setPassword(password());
        target.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        target.setTargetSchema(targetSchema);
        target.setPoolSize(1);
        target.setWriterEngineInsert(true);
        return request;
    }

    private static ColumnDescription column(String name) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setLabelName(name); return column;
    }

    private static List<String> names(List<ColumnDescription> columns) {
        return columns.stream().map(ColumnDescription::getFieldName).toList();
    }

    private static void run(MigrationRequest request) throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request, ignored -> { }, null, runtime).run();
            if (!Boolean.TRUE.equals(result.getIfAllSuccess().get())) {
                throw new IllegalStateException("Migration did not succeed: " + result.getTableResultList());
            }
        }
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"), user(), password());
    }

    private static String user() {
        return System.getProperty("datahub.test.postgres.user", "dataforge_test");
    }

    private static String password() {
        return System.getProperty("datahub.test.postgres.password", "");
    }
}
