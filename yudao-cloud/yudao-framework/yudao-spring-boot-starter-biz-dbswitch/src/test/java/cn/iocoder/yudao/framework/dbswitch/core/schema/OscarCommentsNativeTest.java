package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.oscar.url", matches = "jdbc:oscar://127[.]0[.]0[.]1:[0-9]+/OSRDB")
@EnabledIfSystemProperty(named = "datahub.test.oscar.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_OSCAR_PASSWORD", matches = ".+")
class OscarCommentsNativeTest {
    @Test
    void preservesCommentTextAndPhysicalNamesWithoutExecutingCommentContent() throws Exception {
        String schema = "COMMENT88_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        String name = "Mixed\"Table";
        String field = "Mixed\"Column";
        String tableText = "中文 O'Reilly \"quoted\" \\ path\n'; DROP TABLE sentinel; --";
        String columnText = "字段 '单引号' \"双引号\" \\ end";
        var dialect = DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OSCAR);
        var table = new TableDescription();
        table.setSchemaName(schema); table.setTableName(name); table.setRemarks(tableText);
        var column = new ColumnDescription();
        column.setFieldName(field); column.setRemarks(columnText);
        try (var driver = new VerifiedDriverTestHandle(Path.of(System.getProperty("datahub.test.oscar.driver")), ProductTypeEnum.OSCAR)) {
            var source = driver.dataSource(System.getProperty("datahub.test.oscar.url"), "com.oscar.Driver",
                    System.getenv("DATAHUB_TEST_OSCAR_USER"), System.getenv("DATAHUB_TEST_OSCAR_PASSWORD"), new Properties());
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                assertTrue(connection.getMetaData().getDatabaseProductVersion().contains("V8.0.251217"));
                statement.execute("CREATE SCHEMA " + schema);
                try {
                    statement.execute("CREATE TABLE \"" + schema + "\".\"Mixed\"\"Table\" (\"Mixed\"\"Column\" INTEGER)");
                    for (String sql : dialect.getTableColumnCommentDefinition(table, List.of(column))) statement.execute(sql);
                    try (var tables = connection.getMetaData().getTables(null, schema, name, new String[]{"TABLE"})) {
                        assertTrue(tables.next()); assertEquals(name, tables.getString("TABLE_NAME"));
                        assertEquals(tableText, tables.getString("REMARKS")); assertFalse(tables.next());
                    }
                    try (var columns = connection.getMetaData().getColumns(null, schema, name, field)) {
                        assertTrue(columns.next()); assertEquals(field, columns.getString("COLUMN_NAME"));
                        assertEquals(columnText, columns.getString("REMARKS")); assertFalse(columns.next());
                    }
                    try (var rows = statement.executeQuery("SELECT count(*) FROM \"" + schema + "\".\"Mixed\"\"Table\"")) {
                        assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                    }
                } finally { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
            }
        }
    }
}
