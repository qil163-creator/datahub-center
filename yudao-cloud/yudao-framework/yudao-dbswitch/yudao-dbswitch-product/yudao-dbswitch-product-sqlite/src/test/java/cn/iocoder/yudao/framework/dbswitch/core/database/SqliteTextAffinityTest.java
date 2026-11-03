package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for the upstream text/LOB mapping through the public CREATE path and the real SQLite engine. */
class SqliteTextAffinityTest {
    private final DatabaseSqliteImpl database = new DatabaseSqliteImpl();

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 9999998, 9999999, Integer.MAX_VALUE})
    void textLengthNeverChangesTheColumnToBinary(int length) {
        var column = column("note", Types.VARCHAR, length);
        assertEquals("\"note\"   TEXT", database.getFieldDefinition(column.getMetaData(), List.of(),
                false, false, false).trim());
    }

    @Test
    void binaryColumnsKeepBlobAffinity() {
        var column = column("payload", Types.BLOB, 0);
        assertEquals("\"payload\"   BLOB", database.getFieldDefinition(column.getMetaData(), List.of(),
                false, false, false).trim());
    }

    @Test
    void generatedUnknownLengthAndCharacterLobColumnsHaveRealTextAffinity() throws Exception {
        var columns = List.of(column("unknown_text", Types.VARCHAR, 0),
                column("large_text", Types.LONGVARCHAR, 0), column("clob_text", Types.CLOB, 0),
                column("nclob_text", Types.NCLOB, 0), column("payload", Types.BLOB, 0));
        String ddl = GenerateSqlUtils.getDDLCreateTableSQL(database, columns, List.of(), "main", "text_rows", false);
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement()) {
            statement.execute(ddl);
            try (var metadata = statement.executeQuery("PRAGMA main.table_info(text_rows)")) {
                for (int i = 0; i < columns.size(); i++) {
                    assertTrue(metadata.next());
                    assertEquals(columns.get(i).getFieldName(), metadata.getString("name"));
                    assertEquals(i == 4 ? "BLOB" : "TEXT", metadata.getString("type"));
                }
                assertFalse(metadata.next());
            }
            byte[] bytes = {0, 1, (byte) 255};
            try (var insert = connection.prepareStatement("INSERT INTO text_rows VALUES (?, ?, ?, ?, ?)")) {
                insert.setLong(1, 9007199254740993L);
                insert.setString(2, "中文与'引号");
                insert.setString(3, "");
                insert.setNull(4, Types.NVARCHAR);
                insert.setBytes(5, bytes);
                assertEquals(1, insert.executeUpdate());
            }
            try (var result = statement.executeQuery("SELECT unknown_text, large_text, clob_text, nclob_text, payload, "
                    + "typeof(unknown_text), typeof(payload) FROM text_rows")) {
                assertTrue(result.next());
                // BLOB affinity would keep this numeric bind as INTEGER; TEXT must convert it losslessly.
                assertEquals("text", result.getString(6));
                assertEquals("9007199254740993", result.getObject(1));
                assertEquals("中文与'引号", result.getString(2));
                assertEquals("", result.getString(3));
                assertNull(result.getObject(4));
                assertArrayEquals(bytes, result.getBytes(5));
                assertEquals("blob", result.getString(7));
                assertFalse(result.next());
            }
        }
    }

    private static ColumnDescription column(String name, int jdbcType, int length) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setFieldType(jdbcType);
        column.setPrecisionSize(length);
        column.setDisplaySize(length);
        column.setNullable(true);
        return column;
    }
}
