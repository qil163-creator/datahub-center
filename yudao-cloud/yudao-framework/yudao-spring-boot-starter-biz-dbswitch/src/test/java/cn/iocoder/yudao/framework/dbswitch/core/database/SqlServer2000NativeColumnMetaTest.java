package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserver2000Impl;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.Types;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQL Server 2000 SP3 through an isolated Microsoft JDBC 3.0 driver. */
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_URL", matches = "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;.*")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_DRIVER", matches = ".+")
class SqlServer2000NativeColumnMetaTest {
    private Connection connection;
    private URLClassLoader loader;
    private String table;
    private final DatabaseSqlserver2000Impl database =
            (DatabaseSqlserver2000Impl) DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.SQLSERVER2000);

    @BeforeEach void createOwnedTable() throws Exception {
        var jar = Path.of(System.getenv("DATAHUB_TEST_SQL2000_DRIVER")).toRealPath();
        loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        var driver = (Driver) loader.loadClass("com.microsoft.sqlserver.jdbc.SQLServerDriver").getConstructor().newInstance();
        var properties = new Properties();
        properties.setProperty("user", System.getenv("DATAHUB_TEST_SQL2000_USER"));
        properties.setProperty("password", System.getenv("DATAHUB_TEST_SQL2000_PASSWORD"));
        connection = driver.connect(System.getenv("DATAHUB_TEST_SQL2000_URL"), properties);
        assertNotNull(connection);
        assertEquals("Microsoft SQL Server", connection.getMetaData().getDatabaseProductName());
        assertEquals("8.00.760", connection.getMetaData().getDatabaseProductVersion());
        table = "meta_" + UUID.randomUUID().toString().replace("-", "");
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("CREATE TABLE [dbo].[" + table + "] (identity_id INT IDENTITY(1,1) PRIMARY KEY,"
                    + "label VARCHAR(32) NOT NULL DEFAULT 'preset',amount DECIMAL(10,2) NULL DEFAULT 0,"
                    + "created DATETIME NULL,nlabel NVARCHAR(12) NULL DEFAULT N'fixture')");
            sql.execute("EXEC sp_addextendedproperty 'MS_Description','fixture comment','user','dbo','table','"
                    + table + "','column','label'");
        }
    }

    @AfterEach void releaseOwnedTable() throws Exception {
        try {
            if (connection != null) {
                try (var owned = connection; var sql = owned.createStatement()) {
                    sql.setQueryTimeout(10);
                    if (table != null) {
                        assertTrue(table.matches("meta_[a-f0-9]{32}"));
                        sql.execute("DROP TABLE [dbo].[" + table + "]");
                    }
                }
            }
        } finally {
            if (loader != null) loader.close();
        }
    }

    @Test void emptyTableHasOrderedTypedIdentityAndNativeDefaultsWithoutReadingRows() throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        var columns = database.queryTableColumnMeta(connection, "dbo", table);
        assertEquals(List.of("identity_id", "label", "amount", "created", "nlabel"),
                columns.stream().map(column -> column.getFieldName()).toList());
        assertTrue(columns.getFirst().isAutoIncrement());
        assertFalse(columns.getFirst().isNullable());
        assertEquals(Types.INTEGER, columns.getFirst().getFieldType());
        assertEquals(Types.DECIMAL, columns.get(2).getFieldType());
        assertEquals(10, columns.get(2).getPrecisionSize());
        assertEquals(2, columns.get(2).getScaleSize());
        assertEquals("('preset')", columns.get(1).getDefaultValue());
        assertEquals("(0)", columns.get(2).getDefaultValue());
        assertEquals("(N'fixture')", columns.get(4).getDefaultValue());
        assertEquals("fixture comment", columns.get(1).getRemarks());
        assertEquals(List.of("identity_id"), database.queryTablePrimaryKeys(connection, "dbo", table));
        assertEquals(autoCommit, connection.getAutoCommit());
        assertFalse(connection.isClosed());
    }

    @Test void readOnlyColumnFacadePreservesIdentityAndPreparedBatchValues() throws Exception {
        try (var insert = connection.prepareStatement("INSERT INTO [dbo].[" + table + "] (amount) VALUES(?)")) {
            insert.setBigDecimal(1, new java.math.BigDecimal("12.34")); insert.addBatch();
            insert.setBigDecimal(1, new java.math.BigDecimal("23.45")); insert.addBatch();
            assertArrayEquals(new int[]{1, 1}, insert.executeBatch());
        }
        var columns = database.queryTableColumnMetaOnly(connection, "dbo", table);
        assertEquals(5, columns.size());
        assertTrue(columns.getFirst().isAutoIncrement());
        database.setColumnDefaultValue(connection, "dbo", table, columns);
        assertEquals("fixture comment", columns.get(1).getRemarks());
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            try (var rows = sql.executeQuery("SELECT COUNT(*),SUM(amount) FROM [dbo].[" + table + "]")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
                assertEquals(new java.math.BigDecimal("35.79"), rows.getBigDecimal(2));
                assertFalse(rows.next());
            }
        }
    }

    @Test void fragmentedLegacyDefaultIsRefusedInsteadOfReturningItsFirstChunk() throws Exception {
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("ALTER TABLE [dbo].[" + table + "] ADD extended_default VARCHAR(8000) NULL DEFAULT '"
                    + "x".repeat(5000) + "'");
        }
        var failure = assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                () -> database.queryTableColumnMeta(connection, "dbo", table));
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains("default definition is incomplete"));
        assertFalse(connection.isClosed());
    }

    @Test void legacyCommentDdlUsesUserHierarchyAndEscapesUnicodeLiterals() throws Exception {
        var description = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription();
        description.setSchemaName("dbo"); description.setTableName(table);
        description.setRemarks("owner's table 注释");
        var column = new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription();
        column.setFieldName("label"); column.setRemarks("owner's column 注释");
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("EXEC sp_dropextendedproperty 'MS_Description','user','dbo','table','" + table + "','column','label'");
            var comments = database.getTableColumnCommentDefinition(description, List.of(column));
            assertEquals(2, comments.size());
            for (String comment : comments) {
                assertFalse(comment.contains("[sys]"));
                assertTrue(comment.contains("'user'"));
                sql.execute(comment);
            }
        }
        assertEquals("owner's column 注释", database.queryTableColumnMeta(connection, "dbo", table).get(1).getRemarks());
        column.setRemarks("x".repeat(2001));
        assertThrows(IllegalArgumentException.class, () -> database.getTableColumnCommentDefinition(description, List.of(column)));
    }
}
