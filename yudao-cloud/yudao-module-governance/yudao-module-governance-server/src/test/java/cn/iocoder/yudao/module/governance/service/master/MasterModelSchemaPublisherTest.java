package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static cn.iocoder.yudao.module.governance.service.master.MasterModelSchemaPublisher.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterModelSchemaPublisherTest {
    private Connection connection;
    private DbSwitchRuntime runtime;
    private AbstractDatabase database;
    private MasterDatabaseResolver.ConnectionInfo source;
    private MasterModelSchemaPublisher publisher;
    private SchemaObservation original;
    private final List<MasterColumnDO> columns = List.of(MasterColumnDO.builder().name("code").fieldType(2).fieldLength(64).pk(1).nullable(0).build());

    @BeforeEach
    void setup() throws Exception {
        connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getAutoCommit()).thenReturn(true);
        when(metadata.supportsTransactions()).thenReturn(true);
        when(metadata.supportsDataDefinitionAndDataManipulationTransactions()).thenReturn(true);
        runtime = mock(DbSwitchRuntime.class);
        // 产品入口每次调用工厂会返回新对象；此单测明确固定当前调用使用的数据库对象。
        var products = mock(DbSwitchProductRegistry.class);
        database = DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL);
        when(products.metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(database);
        when(runtime.products()).thenReturn(products);
        source = new MasterDatabaseResolver.ConnectionInfo();
        source.setProductType(ProductTypeEnum.POSTGRESQL);
        source.setSchema("public");
        source.setJdbcUrl("jdbc:postgresql://invalid.test/fixture");
        when(runtime.openConnection(ProductTypeEnum.POSTGRESQL, null, source.getJdbcUrl(), null, null)).thenReturn(connection);
        publisher = new MasterModelSchemaPublisher();
        ReflectionTestUtils.setField(publisher, "dbSwitchRuntime", runtime);
        var provenance = new MetadataProvenance(ProductTypeEnum.POSTGRESQL, "PostgreSQL", "18", "NATIVE_FIXTURE", Instant.now());
        original = new SchemaObservation("public", "person", "scope", "relation", false,
                new TableSchemaMetadata(List.of(), List.of(), provenance, MetadataValue.value(true), MetadataValue.absent()));
    }

    private void knownTarget(MockedStatic<PostgresSchemaChanges> changes, MockedStatic<SchemaSynchronization> plans) throws Exception {
        changes.when(() -> PostgresSchemaChanges.observe(eq(connection), eq(database), eq("public"), eq("person"), any()))
                .thenReturn(original);
        changes.when(() -> PostgresSchemaChanges.execute(eq(connection), eq(database), any(), any(), any(), any()))
                .thenReturn(original);
        plans.when(() -> SchemaSynchronization.plan(eq(connection), eq(database), eq("public"), eq("person"), eq("person"), anyList(), any()))
                .thenReturn(new SchemaSynchronization.Plan(ProductTypeEnum.POSTGRESQL, List.of(), List.of()));
    }

    @Test
    void emptyVerifyStillLocksAndVerifiesBeforeCommit() throws Exception {
        try (var changes = mockStatic(PostgresSchemaChanges.class); var plans = mockStatic(SchemaSynchronization.class)) {
            knownTarget(changes, plans);
            assertEquals(APPLIED, publisher.verify(source, "person", "person", columns).status());
            changes.verify(() -> PostgresSchemaChanges.execute(eq(connection), eq(database),
                    argThat(plan -> plan.steps().isEmpty()), eq(original.precondition()), any(), any()));
            plans.verify(() -> SchemaSynchronization.plan(eq(connection), eq(database), eq("public"), eq("person"), eq("person"), anyList(), any()), times(2));
            verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            verify(connection).setAutoCommit(false);
            verify(connection).commit();
            verify(connection, never()).rollback();
            verify(connection).close();
        }
    }

    @Test
    void verifyCannotExecuteAnOutstandingDdlPlan() throws Exception {
        try (var changes = mockStatic(PostgresSchemaChanges.class); var plans = mockStatic(SchemaSynchronization.class)) {
            knownTarget(changes, plans);
            plans.when(() -> SchemaSynchronization.plan(eq(connection), eq(database), eq("public"), eq("person"), eq("person"), anyList(), any()))
                    .thenReturn(new SchemaSynchronization.Plan(ProductTypeEnum.POSTGRESQL, List.of("ALTER TABLE person ADD COLUMN name varchar(64)"), List.of()));
            var result = publisher.verify(source, "person", "person", columns);
            assertEquals(NOT_APPLIED, result.status());
            assertEquals(List.of("SCHEMA_TARGET_NOT_MATCHED"), result.diagnostics());
            changes.verify(() -> PostgresSchemaChanges.execute(any(), any(), any(), any(), any(), any()), never());
            verify(connection).rollback();
            verify(connection, never()).commit();
        }
    }

    @Test
    void executionFailureWithConfirmedRollbackIsNotAppliedAndRedacted() throws Exception {
        try (var changes = mockStatic(PostgresSchemaChanges.class); var plans = mockStatic(SchemaSynchronization.class)) {
            knownTarget(changes, plans);
            changes.when(() -> PostgresSchemaChanges.execute(eq(connection), eq(database), any(), any(), any(), any()))
                    .thenThrow(new SQLException("vendor fixture password=do-not-expose"));
            var result = publisher.synchronize(source, "person", "person", columns);
            assertEquals(NOT_APPLIED, result.status());
            assertEquals(List.of("SCHEMA_EXECUTION_OR_FRESHNESS_FAILED"), result.diagnostics());
            assertFalse(result.toString().contains("do-not-expose"));
            verify(connection).rollback();
            verify(connection).close();
            verify(connection, never()).commit();
        }
    }

    @Test
    void failedRollbackLeavesExecutionUnknown() throws Exception {
        try (var changes = mockStatic(PostgresSchemaChanges.class); var plans = mockStatic(SchemaSynchronization.class)) {
            knownTarget(changes, plans);
            changes.when(() -> PostgresSchemaChanges.execute(eq(connection), eq(database), any(), any(), any(), any()))
                    .thenThrow(new SQLException("fixture failure"));
            doThrow(new SQLException("fixture rollback failure")).when(connection).rollback();
            var result = publisher.synchronize(source, "person", "person", columns);
            assertEquals(UNKNOWN, result.status());
            assertTrue(result.diagnostics().contains("SCHEMA_ROLLBACK_UNCONFIRMED"));
            verify(connection).close();
        }
    }

    @Test
    void commitFailureIsUnknownEvenIfRollbackConfirms() throws Exception {
        try (var changes = mockStatic(PostgresSchemaChanges.class); var plans = mockStatic(SchemaSynchronization.class)) {
            knownTarget(changes, plans);
            doThrow(new SQLException("fixture commit acknowledgement lost")).when(connection).commit();
            var result = publisher.synchronize(source, "person", "person", columns);
            assertEquals(UNKNOWN, result.status());
            assertEquals(List.of("SCHEMA_COMMIT_UNCONFIRMED"), result.diagnostics());
            verify(connection).rollback();
            verify(connection).close();
        }
    }

    @Test
    void cleanupFailureDoesNotChangeConfirmedCommit() throws Exception {
        try (var changes = mockStatic(PostgresSchemaChanges.class); var plans = mockStatic(SchemaSynchronization.class)) {
            knownTarget(changes, plans);
            doThrow(new SQLException("fixture close acknowledgement lost")).when(connection).close();
            var result = publisher.synchronize(source, "person", "person", columns);
            assertEquals(APPLIED, result.status());
            assertEquals(List.of("SCHEMA_CONNECTION_CLEANUP_UNCONFIRMED"), result.diagnostics());
            verify(connection).commit();
            verify(connection, never()).rollback();
        }
    }

    @Test
    void unsupportedProductNeverConnects() {
        source.setProductType(ProductTypeEnum.MYSQL);
        var result = publisher.synchronize(source, "person", "person", columns);
        assertEquals(NOT_APPLIED, result.status());
        assertEquals(List.of("UNSUPPORTED_SCHEMA_SYNCHRONIZATION_PRODUCT"), result.diagnostics());
        verifyNoInteractions(connection);
        verify(runtime, never()).products();
    }

    @Test
    void unsupportedDdlTransactionCannotReachSchemaPlanning() throws Exception {
        when(connection.getMetaData().supportsDataDefinitionAndDataManipulationTransactions()).thenReturn(false);
        var result = publisher.synchronize(source, "person", "person", columns);
        assertEquals(NOT_APPLIED, result.status());
        assertEquals(List.of("SCHEMA_TRANSACTION_UNSUPPORTED"), result.diagnostics());
        verify(runtime, never()).products();
        verify(connection, never()).setAutoCommit(false);
        verify(connection).close();
    }

    @Test
    void unknownDefaultCannotBeSilentlyRemoved() throws Exception {
        ColumnDescription old = columns.getFirst().toColumnDescription();
        original = new SchemaObservation("public", "person", "scope", "relation", false,
                new TableSchemaMetadata(List.of(old), List.of("code"), original.metadata().provenance(),
                        MetadataValue.value(true), MetadataValue.absent()));
        try (var changes = mockStatic(PostgresSchemaChanges.class); var plans = mockStatic(SchemaSynchronization.class)) {
            knownTarget(changes, plans);
            var result = publisher.synchronize(source, "person", "person", columns);
            assertEquals(NOT_APPLIED, result.status());
            assertEquals(List.of("SCHEMA_DEFAULT_OR_MODEL_DEFINITION_UNSUPPORTED"), result.diagnostics());
            plans.verifyNoInteractions();
            verify(connection).rollback();
            verify(connection, never()).commit();
        }
    }
}
