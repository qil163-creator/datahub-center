package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real 8.00.760 ADD and value round trips through the selected Microsoft JDBC 3.0 driver. */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_URL", matches = "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;.*")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_DRIVER", matches = ".+")
class SqlServer2000NullableAddTest {
    private VerifiedDriverTestHandle drivers;
    private Connection connection;
    private String table;

    @BeforeEach void setup() throws Exception {
        drivers = new VerifiedDriverTestHandle(Path.of(System.getenv("DATAHUB_TEST_SQL2000_DRIVER")), ProductTypeEnum.SQLSERVER2000);
        var source = drivers.dataSource(System.getenv("DATAHUB_TEST_SQL2000_URL"), "com.microsoft.sqlserver.jdbc.SQLServerDriver",
                System.getenv("DATAHUB_TEST_SQL2000_USER"), System.getenv("DATAHUB_TEST_SQL2000_PASSWORD"), new Properties());
        connection = source.getConnection();
        table = "add_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE TABLE [dbo].[" + table + "] ([id] INT NOT NULL PRIMARY KEY)");
        execute("INSERT INTO [dbo].[" + table + "] ([id]) VALUES (1)");
        // Without explicit NULL, this setting makes a plain added column NOT NULL.
        execute("SET ANSI_NULL_DFLT_OFF ON");
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (connection != null) {
                try (var owned = connection) {
                    if (table != null) {
                        assertTrue(table.matches("add_[a-f0-9]{32}"));
                        execute("DROP TABLE [dbo].[" + table + "]");
                    }
                }
            }
        } finally { if (drivers != null) drivers.close(); }
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "" + ColumnMetaData.TYPE_INTEGER + ";9;0;INT",
            "" + ColumnMetaData.TYPE_NUMBER + ";10;2;DECIMAL(10,2)",
            "" + ColumnMetaData.TYPE_BIGNUMBER + ";38;0;DECIMAL(38,0)",
            "" + ColumnMetaData.TYPE_NUMBER + ";38;38;DECIMAL(38,38)",
            "" + ColumnMetaData.TYPE_STRING + ";1;0;NVARCHAR(1)",
            "" + ColumnMetaData.TYPE_STRING + ";4000;0;NVARCHAR(4000)"
    })
    void publicProductAddsNullableColumnWithoutChangingExistingRows(int type, int length, int scale, String nativeType) throws Exception {
        var database = DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.SQLSERVER2000);
        var added = new ColumnDescription();
        added.setDbType(ProductTypeEnum.SQLSERVER2000); added.setFieldName("added");
        added.setValType(type); added.setLength(length); added.setPrecision(scale); added.setNullable(true);
        String ddl = database.getAddColumnSql("dbo", table, added);
        assertEquals("ALTER TABLE [dbo].[" + table + "] ADD [added] " + nativeType + " NULL", ddl);
        execute(ddl);

        var metadata = database.queryTableColumnMeta(connection, "dbo", table);
        assertEquals(java.util.List.of("id", "added"), metadata.stream().map(ColumnDescription::getFieldName).toList());
        var observed = metadata.getLast();
        assertTrue(observed.isNullable()); assertFalse(observed.isAutoIncrement()); assertNull(observed.getDefaultValue());
        try (var statement = connection.prepareStatement("SELECT [added] FROM [dbo].[" + table + "] WHERE [id]=1")) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next()); assertNull(rows.getObject(1)); assertFalse(rows.next());
            }
        }

        Object value = type == ColumnMetaData.TYPE_STRING ? "汉".repeat(length)
                : "INT".equals(nativeType) ? Integer.valueOf(42)
                : scale == 0 ? new BigDecimal("12345678901234567890123456789012345678")
                : new BigDecimal("0." + "1".repeat(scale));
        try (var statement = connection.prepareStatement("UPDATE [dbo].[" + table + "] SET [added]=? WHERE [id]=1")) {
            statement.setQueryTimeout(10); statement.setObject(1, value); assertEquals(1, statement.executeUpdate());
        }
        try (var statement = connection.prepareStatement("SELECT [added] FROM [dbo].[" + table + "] WHERE [id]=1")) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                if (value instanceof BigDecimal decimal) assertEquals(0, decimal.compareTo(rows.getBigDecimal(1)));
                else assertEquals(value, rows.getObject(1));
                assertFalse(rows.next());
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.setQueryTimeout(10); statement.execute(sql); }
    }
}
