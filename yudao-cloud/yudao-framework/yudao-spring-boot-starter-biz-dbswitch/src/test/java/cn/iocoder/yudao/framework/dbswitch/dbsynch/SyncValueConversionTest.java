package cn.iocoder.yudao.framework.dbswitch.dbsynch;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.db2.DB2DatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.dm.DmDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.hive.HiveDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mssql.SqlServerDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mysql.MySqlDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.oracle.OracleDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.oscar.OscarDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql.PostgresqlDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.sqlite.Sqlite3DatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.tdengine.TdengineDatabaseSyncImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Struct;
import java.sql.Types;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyncValueConversionTest {
    static Stream<Class<? extends AbstractDatabaseSynchronize>> syncs() {
        // Doris has a native nontransactional preparation contract, covered by
        // DorisNativeWriteOutcomeTest.preparedPublicWriterAndSyncRejectInvalidBatchBeforeConnecting.
        return Stream.of(DB2DatabaseSyncImpl.class, DmDatabaseSyncImpl.class,
                HiveDatabaseSyncImpl.class, SqlServerDatabaseSyncImpl.class, MySqlDatabaseSyncImpl.class,
                OracleDatabaseSyncImpl.class, OscarDatabaseSyncImpl.class, PostgresqlDatabaseSyncImpl.class,
                Sqlite3DatabaseSyncImpl.class, TdengineDatabaseSyncImpl.class);
    }

    @ParameterizedTest @MethodSource("syncs")
    void insertFailureNeverWritesValidPrefixOrMutatesRows(Class<? extends AbstractDatabaseSynchronize> type) throws Exception {
        var source = mock(DataSource.class); var sync = type.getConstructor(DataSource.class).newInstance(source);
        prepare(sync, List.of("id", "value"), new int[]{Types.INTEGER, Types.VARCHAR}, new int[]{Types.VARCHAR, Types.INTEGER});
        var clob = mock(Clob.class); when(clob.getCharacterStream()).thenReturn(new StringReader("first"));
        var unsupported = mock(Struct.class);
        Object firstKey = sync instanceof TdengineDatabaseSyncImpl ? java.time.Instant.ofEpochSecond(1) : 1;
        Object secondKey = sync instanceof TdengineDatabaseSyncImpl ? java.time.Instant.ofEpochSecond(2) : 2;
        var rows = List.of(new Object[]{firstKey, clob}, new Object[]{secondKey, unsupported});
        assertThrows(IllegalArgumentException.class, () -> sync.executeInsert(rows));
        assertSame(clob, rows.get(0)[1]); assertSame(unsupported, rows.get(1)[1]);
        verify(clob).free(); verifyNoInteractions(source);
    }

    @Test void updateAndDeleteFailuresAlsoOccurBeforeBorrowingConnection() {
        for (boolean update : new boolean[]{true, false}) {
            var source = mock(DataSource.class); var sync = new PostgresqlDatabaseSyncImpl(source);
            prepare(sync, List.of("id", "value"), new int[]{Types.INTEGER, Types.VARCHAR}, new int[]{Types.VARCHAR, Types.INTEGER});
            var bad = mock(Struct.class);
            var rows = update ? List.of(new Object[]{1, "first"}, new Object[]{2, bad})
                    : List.of(new Object[]{1, "first"}, new Object[]{bad, "second"});
            assertThrows(IllegalArgumentException.class, () -> { if (update) sync.executeUpdate(rows); else sync.executeDelete(rows); });
            verifyNoInteractions(source);
        }
    }

    @Test void oracleUpdateConvertsAgainstInputOrderThenBindsLobsBeforePrimaryKey() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeBatch()).thenReturn(new int[]{1});
        var sync = new OracleDatabaseSyncImpl(source);
        prepare(sync, List.of("id", "text", "binary"), new int[]{Types.INTEGER, Types.CLOB, Types.BLOB},
                new int[]{Types.CLOB, Types.BLOB, Types.INTEGER});
        var clob = mock(Clob.class); when(clob.getCharacterStream()).thenReturn(new StringReader("first\nsecond"));
        var blob = mock(Blob.class); when(blob.length()).thenReturn(3L);
        when(blob.getBinaryStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        doAnswer(call -> {
            Reader reader = call.getArgument(1); var target = new StringWriter(); reader.transferTo(target);
            assertEquals("first\nsecond", target.toString()); return null;
        }).when(statement).setClob(eq(1), any(Reader.class), eq(12L));
        doAnswer(call -> {
            InputStream input = call.getArgument(1); assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes()); return null;
        }).when(statement).setBlob(eq(2), any(InputStream.class), eq(3L));
        var row = new Object[]{7, clob, blob}; var rows = List.<Object[]>of(row);
        assertEquals(1, sync.executeUpdate(rows));
        verify(statement).setClob(eq(1), any(Reader.class), eq(12L));
        verify(statement).setBlob(eq(2), any(InputStream.class), eq(3L));
        verify(statement).setObject(3, 7, Types.INTEGER); verify(connection).commit();
        verify(clob).free(); verify(blob).free(); assertArrayEquals(new Object[]{7, clob, blob}, row);
    }

    private static void prepare(AbstractDatabaseSynchronize sync, List<String> fields, int[] insertTypes, int[] updateTypes) {
        ReflectionTestUtils.setField(sync, "fieldOrders", fields); ReflectionTestUtils.setField(sync, "pksOrders", List.of("id"));
        ReflectionTestUtils.setField(sync, "insertArgsType", insertTypes); ReflectionTestUtils.setField(sync, "updateArgsType", updateTypes);
        ReflectionTestUtils.setField(sync, "deleteArgsType", new int[]{Types.INTEGER});
        ReflectionTestUtils.setField(sync, "insertStatementSql", "INSERT INTO fixture VALUES (?, ?)");
        ReflectionTestUtils.setField(sync, "updateStatementSql", "UPDATE fixture SET value = ? WHERE id = ?");
        ReflectionTestUtils.setField(sync, "deleteStatementSql", "DELETE FROM fixture WHERE id = ?");
        // Native synchronizers need their prepared native shape, not only JDBC type arrays.
        // The test still requires whole-batch conversion to fail before borrowing a connection.
        if (sync instanceof HiveDatabaseSyncImpl) {
            var columns = new java.util.LinkedHashMap<String, cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription>();
            for (int index = 0; index < fields.size(); index++) {
                var column = new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription();
                column.setFieldName(fields.get(index)); column.setFieldType(insertTypes[index]); columns.put(fields.get(index), column);
            }
            ReflectionTestUtils.setField(sync, "nativeColumns", java.util.Map.copyOf(columns));
            ReflectionTestUtils.setField(sync, "acid", new cn.iocoder.yudao.framework.dbswitch.product.hive.HiveAcidTable(java.util.Set.of()));
        }
        if (sync instanceof TdengineDatabaseSyncImpl) {
            var columns = List.of(
                    new cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport.Column("id", Types.TIMESTAMP, "TIMESTAMP", 0, 0),
                    new cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport.Column("value", Types.NVARCHAR, "NCHAR", 128, 0));
            ReflectionTestUtils.setField(sync, "metadata", new cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport.Table(columns, List.of("id"), 3));
        }
    }
}
