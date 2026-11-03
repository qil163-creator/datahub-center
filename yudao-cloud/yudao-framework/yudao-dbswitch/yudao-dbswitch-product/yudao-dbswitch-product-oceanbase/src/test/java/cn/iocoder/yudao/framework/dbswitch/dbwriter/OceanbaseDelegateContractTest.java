package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.oceanbase.OceanbaseDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oceanbase.OceanbaseWriterImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JDBC mock routing/contract tests; these do not certify a real OceanBase server or driver. */
class OceanbaseDelegateContractTest {
    private static final List<String> FIELDS = List.of("id", "value");
    private static final List<Object[]> ONE_ROW = List.<Object[]>of(new Object[]{1, "中文"});
    private static final List<Object[]> TWO_ROWS = List.of(new Object[]{1, "first"}, new Object[]{2, "second"});
    private static final BatchLimits ONE_ROW_LIMIT = new BatchLimits(1, 1024, 512, 10, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(4096, false, false, 17, 0, 0, 250, 0);

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void dataSourceDdlProbesActualTargetModeBeforeRenderingAndReturnsTheBorrowedConnection(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        var service = new MetaDataByDataSourceServiceImpl(fixture.source, ProductTypeEnum.OCEANBASE);

        List<String> ddl = service.getDDLCreateTableSQL(ProductTypeEnum.OCEANBASE,
                ddlColumns(), List.of(), "fixture", "rows", "", false, Map.of());

        assertTrue(ddl.getFirst().contains(fixture.qualifiedTable()), ddl.toString());
        verify(fixture.source, times(1)).getConnection();
        verify(fixture.modeStatement).executeQuery();
        verify(fixture.modeStatement).close();
        verify(fixture.modeConnection).close();
        verify(fixture.modeConnection, never()).commit();
        verify(fixture.modeConnection, never()).rollback();
        verifyNoInteractions(fixture.metadataConnection, fixture.writeConnection, fixture.writeStatement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void jdbcDdlUsesCallerConnectionAndNeverTakesOwnership(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        JdbcConnectionProvider provider = mock(JdbcConnectionProvider.class);
        var service = new MetaDataByJdbcServiceImpl(ProductTypeEnum.OCEANBASE,
                DbSwitchProductRegistry.discover(), provider);

        String ddl = service.getDDLCreateTableSQL(fixture.modeConnection, ProductTypeEnum.OCEANBASE,
                ddlColumns(), List.of(), "fixture", "rows", false);

        assertTrue(ddl.contains(fixture.qualifiedTable()), ddl);
        verify(fixture.modeStatement).executeQuery();
        verify(fixture.modeStatement).close();
        verify(fixture.modeConnection, never()).close();
        verify(fixture.modeConnection, never()).commit();
        verify(fixture.modeConnection, never()).rollback();
        verifyNoInteractions(provider, fixture.source);
    }

    @Test
    void offlineOceanbaseDdlNeverGuessesMysqlOrReusesAnUnrelatedConnection() {
        JdbcConnectionProvider provider = mock(JdbcConnectionProvider.class);
        var service = new MetaDataByJdbcServiceImpl(ProductTypeEnum.OCEANBASE,
                DbSwitchProductRegistry.discover(), provider);

        assertThrows(IllegalStateException.class, () -> service.getDDLCreateTableSQL(ProductTypeEnum.OCEANBASE,
                ddlColumns(), List.of(), "fixture", "rows", false));
        assertThrows(IllegalStateException.class,
                () -> new DatabaseOceanbaseImpl().getQuotedSchemaTableCombination("fixture", "rows"));
        verifyNoInteractions(provider);
    }

    @Test
    void fixedDialectOfflineDdlStillNeedsNoConnection() {
        JdbcConnectionProvider provider = mock(JdbcConnectionProvider.class);
        var service = new MetaDataByJdbcServiceImpl(ProductTypeEnum.MYSQL,
                DbSwitchProductRegistry.discover(), provider);
        assertTrue(service.getDDLCreateTableSQL(ProductTypeEnum.MYSQL, ddlColumns(), List.of(),
                "fixture", "rows", false).contains("`fixture`.`rows`"));
        verifyNoInteractions(provider);
    }

    @Test
    void unavailableModePreventsDdlGenerationAndReturnsBorrowedConnection() throws Exception {
        Fixture fixture = new Fixture("UNKNOWN");
        var service = new MetaDataByDataSourceServiceImpl(fixture.source, ProductTypeEnum.OCEANBASE);
        assertThrows(IllegalStateException.class, () -> service.getDDLCreateTableSQL(ProductTypeEnum.OCEANBASE,
                ddlColumns(), List.of(), "fixture", "rows", "", false, Map.of()));
        verify(fixture.modeConnection).close();
        verifyNoInteractions(fixture.metadataConnection, fixture.writeConnection, fixture.writeStatement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void metadataInstanceRejectsAConnectionFromTheOtherCompatibilityMode(String mode) throws Exception {
        Fixture first = new Fixture(mode);
        Fixture other = new Fixture("MYSQL".equals(mode) ? "ORACLE" : "MYSQL");
        DatabaseOceanbaseImpl dialect = new DatabaseOceanbaseImpl();
        dialect.prepareDialect(first.modeConnection);
        assertThrows(IllegalStateException.class, () -> dialect.prepareDialect(other.modeConnection));
        assertEquals(first.qualifiedTable(), dialect.getQuotedSchemaTableCombination("fixture", "rows"));
        verify(first.modeConnection, never()).close();
        verify(other.modeConnection, never()).close();
    }

    private static List<ColumnDescription> ddlColumns() {
        ColumnDescription id = new ColumnDescription();
        id.setFieldName("id");
        id.setNullable(true);
        SqlTypeSemantics.applyDeclaredType(id, "int", null, null, null);
        return List.of(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void writerRequiresSuccessfulPreparationForBothEntryPoints(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseWriterImpl writer = new OceanbaseWriterImpl(fixture.source);
        clearInvocations(fixture.source);

        assertThrows(IllegalStateException.class, () -> writer.write(FIELDS, ONE_ROW));
        assertThrows(IllegalStateException.class, () -> writer.writeWithReceipt(FIELDS, ONE_ROW));
        verifyNoInteractions(fixture.source, fixture.writeConnection, fixture.writeStatement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void failedWriterReprepareCannotWritePreviouslyPreparedTable(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseWriterImpl writer = new OceanbaseWriterImpl(fixture.source);
        writer.prepareWrite("fixture", "rows", FIELDS);
        when(fixture.source.getConnection()).thenThrow(new SQLException("private metadata failure"));
        assertThrows(RuntimeException.class, () -> writer.prepareWrite("fixture", "other_rows", FIELDS));
        doReturn(fixture.writeConnection).when(fixture.source).getConnection();
        clearInvocations(fixture.source);

        assertThrows(IllegalStateException.class, () -> writer.write(FIELDS, ONE_ROW));
        assertThrows(IllegalStateException.class, () -> writer.writeWithReceipt(FIELDS, ONE_ROW));
        verifyNoInteractions(fixture.source, fixture.writeConnection, fixture.writeStatement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void synchronizerRequiresSuccessfulPreparationForEveryDml(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseDatabaseSyncImpl synchronizer = new OceanbaseDatabaseSyncImpl(fixture.source);
        clearInvocations(fixture.source);

        assertThrows(IllegalStateException.class, () -> synchronizer.executeInsert(ONE_ROW));
        assertThrows(IllegalStateException.class, () -> synchronizer.executeUpdate(ONE_ROW));
        assertThrows(IllegalStateException.class, () -> synchronizer.executeDelete(ONE_ROW));
        verifyNoInteractions(fixture.source, fixture.writeConnection, fixture.writeStatement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void failedSynchronizerReprepareCannotMutatePreviouslyPreparedTable(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseDatabaseSyncImpl synchronizer = new OceanbaseDatabaseSyncImpl(fixture.source);
        synchronizer.prepare("fixture", "rows", FIELDS, List.of("id"));
        when(fixture.source.getConnection()).thenThrow(new SQLException("private metadata failure"));
        assertThrows(RuntimeException.class,
                () -> synchronizer.prepare("fixture", "other_rows", FIELDS, List.of("id")));
        doReturn(fixture.writeConnection).when(fixture.source).getConnection();
        clearInvocations(fixture.source);

        assertThrows(IllegalStateException.class, () -> synchronizer.executeInsert(ONE_ROW));
        assertThrows(IllegalStateException.class, () -> synchronizer.executeUpdate(ONE_ROW));
        assertThrows(IllegalStateException.class, () -> synchronizer.executeDelete(ONE_ROW));
        verifyNoInteractions(fixture.source, fixture.writeConnection, fixture.writeStatement);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void writerRejectsCustomRowLimitBeforeBorrowingWriteConnection(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseWriterImpl writer = new OceanbaseWriterImpl(fixture.source);
        writer.configureBatchLimits(ONE_ROW_LIMIT);
        writer.prepareWrite("fixture", "rows", FIELDS);
        clearInvocations(fixture.source);

        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(FIELDS, TWO_ROWS));

        verifyNoInteractions(fixture.source, fixture.writeConnection, fixture.writeStatement);
        assertEquals(2, TWO_ROWS.size());
        assertArrayEquals(new Object[]{1, "first"}, TWO_ROWS.getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void synchronizerAppliesCustomLimitToEveryDmlBeforeBorrowingWriteConnection(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseDatabaseSyncImpl synchronizer = new OceanbaseDatabaseSyncImpl(fixture.source);
        synchronizer.configureBatchLimits(ONE_ROW_LIMIT);
        synchronizer.prepare("fixture", "rows", FIELDS, List.of("id"));
        clearInvocations(fixture.source);

        assertThrows(BatchRejectedException.class, () -> synchronizer.executeInsert(TWO_ROWS));
        assertThrows(BatchRejectedException.class, () -> synchronizer.executeUpdate(TWO_ROWS));
        assertThrows(BatchRejectedException.class, () -> synchronizer.executeDelete(TWO_ROWS));

        verifyNoInteractions(fixture.source, fixture.writeConnection, fixture.writeStatement);
        assertArrayEquals(new Object[]{2, "second"}, TWO_ROWS.get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void writerConfiguresActualDelegateAndKeepsItsJdbcResult(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseWriterImpl writer = new OceanbaseWriterImpl(fixture.source);
        writer.configure(OPTIONS);
        writer.configureBatchLimits(ONE_ROW_LIMIT);
        writer.prepareWrite("fixture", "rows", FIELDS);

        BatchWriteResult result = writer.writeWithReceipt(FIELDS, ONE_ROW);

        assertSame(OPTIONS, ReflectionTestUtils.getField(
                ReflectionTestUtils.getField(writer, "delegate"), "runtimeOptions"));
        assertSame(fixture.source, writer.getDataSource());
        assertEquals(1, result.acknowledgedRows());
        assertNull(result.receipt(), "ordinary JDBC must not fabricate a native commit witness");
        verify(fixture.writeConnection).prepareStatement(contains(fixture.qualifiedTable()));
        verify(fixture.writeStatement).setObject(1, 1, Types.INTEGER);
        verify(fixture.writeStatement).setObject(2, "中文", Types.VARCHAR);
        verify(fixture.writeStatement).setQueryTimeout(OPTIONS.queryTimeoutSeconds());
        verify(fixture.writeStatement).executeBatch();
        verify(fixture.writeConnection).commit();
        verify(fixture.modeStatement).setQueryTimeout(5);
        assertArrayEquals(new Object[]{1, "中文"}, ONE_ROW.getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void unsupportedStatementTimeoutRejectsBeforeSendingRows(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseWriterImpl writer = new OceanbaseWriterImpl(fixture.source);
        writer.configure(OPTIONS);
        writer.prepareWrite("fixture", "rows", FIELDS);
        doThrow(new java.sql.SQLFeatureNotSupportedException("private driver detail"))
                .when(fixture.writeStatement).setQueryTimeout(OPTIONS.queryTimeoutSeconds());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> writer.writeWithReceipt(FIELDS, ONE_ROW));

        assertNull(failure.getCause());
        assertFalse(UnconfirmedWriteException.class.isInstance(failure));
        verify(fixture.writeStatement, never()).setObject(anyInt(), any(), anyInt());
        verify(fixture.writeStatement, never()).executeBatch();
        verify(fixture.writeConnection, never()).commit();
        verify(fixture.writeConnection).rollback();
        verify(fixture.writeStatement).close();
        verify(fixture.writeConnection).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void writerPreservesUnknownCommitOutcomeWithoutRetry(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseWriterImpl writer = new OceanbaseWriterImpl(fixture.source);
        writer.configureBatchLimits(ONE_ROW_LIMIT);
        writer.prepareWrite("fixture", "rows", FIELDS);
        doThrow(new SQLException("driver detail must not escape")).when(fixture.writeConnection).commit();

        UnconfirmedWriteException failure = assertThrows(UnconfirmedWriteException.class,
                () -> writer.writeWithReceipt(FIELDS, ONE_ROW));

        assertNull(failure.getCause());
        verify(fixture.writeStatement).executeBatch();
        verify(fixture.writeConnection).commit();
        verify(fixture.writeConnection).rollback();
        verify(fixture.writeConnection).abort(any());
        verify(fixture.writeConnection).close();
        verify(fixture.writeConnection, never()).setAutoCommit(true);
    }

    @ParameterizedTest
    @CsvSource({"MYSQL,INSERT", "MYSQL,UPDATE", "MYSQL,DELETE",
            "ORACLE,INSERT", "ORACLE,UPDATE", "ORACLE,DELETE"})
    void synchronizerConfiguresMetadataAndEveryDmlThroughTheRealDelegate(String mode, String operation) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseDatabaseSyncImpl synchronizer = new OceanbaseDatabaseSyncImpl(fixture.source);
        synchronizer.configure(OPTIONS);
        synchronizer.prepare("fixture", "rows", FIELDS, List.of("id"));

        long confirmed = switch (operation) {
            case "INSERT" -> synchronizer.executeInsert(ONE_ROW);
            case "UPDATE" -> synchronizer.executeUpdate(ONE_ROW);
            case "DELETE" -> synchronizer.executeDelete(ONE_ROW);
            default -> throw new AssertionError(operation);
        };

        assertEquals(1, confirmed);
        verify(fixture.metadataStatement).setQueryTimeout(OPTIONS.queryTimeoutSeconds());
        verify(fixture.writeStatement).setQueryTimeout(OPTIONS.queryTimeoutSeconds());
        var order = inOrder(fixture.writeStatement, fixture.writeConnection);
        order.verify(fixture.writeStatement).setQueryTimeout(OPTIONS.queryTimeoutSeconds());
        order.verify(fixture.writeStatement).executeBatch();
        order.verify(fixture.writeConnection).commit();
    }

    @ParameterizedTest
    @CsvSource({"MYSQL,INSERT", "MYSQL,UPDATE", "MYSQL,DELETE",
            "ORACLE,INSERT", "ORACLE,UPDATE", "ORACLE,DELETE"})
    void synchronizerKeepsUnknownDmlOutcomeFromActualDelegate(String mode, String operation) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseDatabaseSyncImpl synchronizer = new OceanbaseDatabaseSyncImpl(fixture.source);
        synchronizer.configureBatchLimits(ONE_ROW_LIMIT);
        synchronizer.prepare("fixture", "rows", FIELDS, List.of("id"));
        when(fixture.writeStatement.executeBatch()).thenThrow(new SQLException("driver detail must not escape"));

        UnconfirmedWriteException failure = assertThrows(UnconfirmedWriteException.class, () -> {
            switch (operation) {
                case "INSERT" -> synchronizer.executeInsert(ONE_ROW);
                case "UPDATE" -> synchronizer.executeUpdate(ONE_ROW);
                case "DELETE" -> synchronizer.executeDelete(ONE_ROW);
                default -> throw new AssertionError(operation);
            }
        });

        assertNull(failure.getCause());
        assertSame(fixture.source, synchronizer.getDataSource());
        verify(fixture.writeConnection).prepareStatement(argThat(sql ->
                sql.startsWith(operation) && sql.contains(fixture.qualifiedTable())));
        verify(fixture.writeStatement).executeBatch();
        verify(fixture.writeConnection, never()).commit();
        verify(fixture.writeConnection).rollback();
        verify(fixture.writeConnection).abort(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "ORACLE"})
    void receiptApiReturnsDelegateResultWithoutRebuildingItOrCallingLegacyWrite(String mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        OceanbaseWriterImpl writer = new OceanbaseWriterImpl(fixture.source);
        IDatabaseWriter delegate = spy((IDatabaseWriter) ReflectionTestUtils.getField(writer, "delegate"));
        ReflectionTestUtils.setField(writer, "delegate", delegate);
        writer.prepareWrite("fixture", "rows", FIELDS);
        BatchWriteResult expected = new BatchWriteResult(7, null);
        doReturn(expected).when(delegate).writeWithReceipt(FIELDS, ONE_ROW);

        assertSame(expected, writer.writeWithReceipt(FIELDS, ONE_ROW));

        verify(delegate).writeWithReceipt(FIELDS, ONE_ROW);
        verify(delegate, never()).write(anyList(), anyList());
        verifyNoInteractions(fixture.writeConnection, fixture.writeStatement);
    }

    private static final class Fixture {
        private final String mode;
        private final DataSource source = mock(DataSource.class);
        private final Connection modeConnection = mock(Connection.class);
        private final PreparedStatement modeStatement = mock(PreparedStatement.class);
        private final Connection metadataConnection = mock(Connection.class);
        private final Statement metadataStatement = mock(Statement.class);
        private final Connection writeConnection = mock(Connection.class);
        private final PreparedStatement writeStatement = mock(PreparedStatement.class);

        private Fixture(String mode) throws SQLException {
            this.mode = mode;
            when(source.getConnection()).thenReturn(modeConnection, metadataConnection, writeConnection);
            ResultSet modeResult = mock(ResultSet.class);
            when(modeConnection.prepareStatement(anyString())).thenReturn(modeStatement);
            when(modeStatement.executeQuery()).thenReturn(modeResult);
            when(modeResult.next()).thenReturn(true, false);
            when(modeResult.getString(2)).thenReturn(mode);

            ResultSet metadataResult = mock(ResultSet.class);
            ResultSetMetaData columns = mock(ResultSetMetaData.class);
            when(metadataConnection.createStatement()).thenReturn(metadataStatement);
            when(metadataStatement.executeQuery(anyString())).thenReturn(metadataResult);
            when(metadataResult.getMetaData()).thenReturn(columns);
            when(columns.getColumnCount()).thenReturn(2);
            when(columns.getColumnName(1)).thenReturn("id");
            when(columns.getColumnLabel(1)).thenReturn("id");
            when(columns.getColumnType(1)).thenReturn(Types.INTEGER);
            when(columns.getColumnName(2)).thenReturn("value");
            when(columns.getColumnLabel(2)).thenReturn("value");
            when(columns.getColumnType(2)).thenReturn(Types.VARCHAR);

            when(writeConnection.getAutoCommit()).thenReturn(true);
            when(writeConnection.prepareStatement(anyString())).thenReturn(writeStatement);
            when(writeStatement.executeBatch()).thenReturn(new int[]{1});
        }

        private String qualifiedTable() {
            return "MYSQL".equals(mode) ? "`fixture`.`rows`" : "\"fixture\".\"rows\"";
        }
    }
}
