package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证隐式提交不能冒充回滚，以及异步确认失败后的持久未知结果。 */
class SchemaChangesOutcomeTest {

    @Test
    void postgresCommitReceiptLossIsUnknownWithoutRollbackAssumption() throws Exception {
        var connection = mock(Connection.class);
        var database = mock(AbstractDatabase.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.POSTGRESQL);
        var metadata = new cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata(List.of(), List.of(),
                cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance.unknown(),
                cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.value(true),
                cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.absent());
        var observed = new SchemaObservation("public", "new_table", "scope", "relation", false, metadata);
        var plan = SchemaChanges.plan(database, "public", "new_table", List.of("CREATE TABLE public.new_table (id bigint)"));
        doThrow(new SQLException("commit receipt lost")).when(connection).commit();
        try (var changes = mockStatic(PostgresSchemaChanges.class)) {
            changes.when(() -> PostgresSchemaChanges.execute(any(), any(), any(), any(), any(), any())).thenReturn(observed);

            assertEquals(SchemaChanges.Outcome.UNKNOWN, SchemaChanges.execute(connection, database, plan,
                    observed.precondition(), MetadataReadOptions.fresh(), ignored -> { }).outcome());
        }
        verify(connection, never()).rollback();
        verify(connection, never()).setAutoCommit(true);
    }

    @Test
    void postgresRollbackFailureCannotBeReportedAsRolledBack() throws Exception {
        var connection = mock(Connection.class);
        var database = mock(AbstractDatabase.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.POSTGRESQL);
        var plan = SchemaChanges.plan(database, "public", "new_table", List.of("CREATE TABLE public.new_table (id bigint)"));
        var expected = new SchemaPrecondition("public", "new_table", "scope", null, false, false, "before");
        doThrow(new SQLException("rollback receipt lost")).when(connection).rollback();
        try (var changes = mockStatic(PostgresSchemaChanges.class)) {
            changes.when(() -> PostgresSchemaChanges.execute(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new SQLException("execution failed"));

            assertEquals(SchemaChanges.Outcome.UNKNOWN, SchemaChanges.execute(connection, database, plan,
                    expected, MetadataReadOptions.fresh(), ignored -> { }).outcome());
        }
        verify(connection, never()).setAutoCommit(true);
    }

    @Test
    void dorisAsyncTimeoutIsUnknownAndNeverRollsBackOrRepeatsDdl() throws Exception {
        var fixture = fixture();
        doNothing().doThrow(new SQLException("asynchronous schema operation timeout"))
                .when(fixture.database).awaitSchemaChange(any(), anyString(), anyString(), anyInt());
        var expected = SchemaChanges.observe(fixture.connection, fixture.database, "warehouse", "new_table",
                MetadataReadOptions.fresh()).precondition();

        var result = SchemaChanges.execute(fixture.connection, fixture.database, fixture.plan, expected,
                MetadataReadOptions.fresh(), ignored -> { });

        assertEquals(SchemaChanges.Outcome.UNKNOWN, result.outcome());
        verify(fixture.statement).execute(fixture.plan.steps().getFirst().sql());
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).setAutoCommit(false);
    }

    @Test
    void dorisStatementFailureIsUnknownEvenWhenNoSuccessWasReturned() throws Exception {
        var fixture = fixture();
        var expected = SchemaChanges.observe(fixture.connection, fixture.database, "warehouse", "new_table",
                MetadataReadOptions.fresh()).precondition();
        doThrow(new SQLException("connection lost after sending DDL"))
                .when(fixture.statement).execute(fixture.plan.steps().getFirst().sql());

        var result = SchemaChanges.execute(fixture.connection, fixture.database, fixture.plan, expected,
                MetadataReadOptions.fresh(), ignored -> { });

        assertEquals(SchemaChanges.Outcome.UNKNOWN, result.outcome());
        verify(fixture.connection, never()).rollback();
        verify(fixture.database).awaitSchemaChange(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void pendingPreviousDorisJobDoesNotStartAnotherDdlOrReleaseReconciliation() throws Exception {
        var fixture = fixture();
        var expected = SchemaChanges.observe(fixture.connection, fixture.database, "warehouse", "new_table",
                MetadataReadOptions.fresh()).precondition();
        doThrow(new SQLException("previous job is still active"))
                .when(fixture.database).awaitSchemaChange(any(), anyString(), anyString(), anyInt());

        assertEquals(SchemaChanges.Outcome.NOT_STARTED, SchemaChanges.execute(fixture.connection, fixture.database,
                fixture.plan, expected, MetadataReadOptions.fresh(), ignored -> { }).outcome());
        verify(fixture.statement, never()).execute(anyString());
        var finalized = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(SQLException.class, () -> SchemaChanges.withGuardedObservation(fixture.connection,
                fixture.database, "warehouse", "new_table", MetadataReadOptions.fresh(), ignored -> finalized.set(true)));
        assertFalse(finalized.get());
    }

    @Test
    void staleFrozenScopeNeverSendsDdl() throws Exception {
        var fixture = fixture();
        var expected = new SchemaPrecondition("warehouse", "new_table", "wrong-scope", null, false, false, "before");

        var result = SchemaChanges.execute(fixture.connection, fixture.database, fixture.plan, expected,
                MetadataReadOptions.fresh(), ignored -> { });

        assertEquals(SchemaChanges.Outcome.NOT_STARTED, result.outcome());
        verify(fixture.statement, never()).execute(anyString());
    }

    @Test
    void missingTargetAfterAcknowledgedDdlCannotBeCommittedSuccess() throws Exception {
        var fixture = fixture();
        var expected = SchemaChanges.observe(fixture.connection, fixture.database, "warehouse", "new_table",
                MetadataReadOptions.fresh()).precondition();

        var result = SchemaChanges.execute(fixture.connection, fixture.database, fixture.plan, expected,
                MetadataReadOptions.fresh(), ignored -> { });

        assertEquals(SchemaChanges.Outcome.UNKNOWN, result.outcome());
        assertFalse(result.observation().precondition().tableExists());
    }

    @Test
    void dorisSuccessfulCreateUsesNativeObservationWithoutTransactionEmulation() throws Exception {
        var fixture = fixture();
        var expected = SchemaChanges.observe(fixture.connection, fixture.database, "warehouse", "new_table",
                MetadataReadOptions.fresh()).precondition();
        makeTargetAppearAfterDdl(fixture);

        var result = SchemaChanges.execute(fixture.connection, fixture.database, fixture.plan, expected,
                MetadataReadOptions.fresh(), ignored -> { }, observed -> observed.metadata().primaryKeys().equals(List.of("id")));

        assertEquals(SchemaChanges.Outcome.COMMITTED, result.outcome());
        assertTrue(result.observation().precondition().tableExists());
        verify(fixture.database).readModelingSchema(fixture.connection, "warehouse", "new_table", 10);
        verify(fixture.database, times(2)).awaitSchemaChange(fixture.connection, "warehouse", "new_table", 10);
        verify(fixture.connection, never()).setAutoCommit(false);
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).rollback();
    }

    @Test
    void dorisNativeResultMismatchAfterAcknowledgementKeepsUnknown() throws Exception {
        var fixture = fixture();
        var expected = SchemaChanges.observe(fixture.connection, fixture.database, "warehouse", "new_table",
                MetadataReadOptions.fresh()).precondition();
        makeTargetAppearAfterDdl(fixture);

        var result = SchemaChanges.execute(fixture.connection, fixture.database, fixture.plan, expected,
                MetadataReadOptions.fresh(), ignored -> { }, observed -> false);

        assertEquals(SchemaChanges.Outcome.UNKNOWN, result.outcome());
        assertTrue(result.observation().precondition().tableExists());
        verify(fixture.connection, never()).rollback();
    }

    private void makeTargetAppearAfterDdl(Fixture fixture) throws Exception {
        var created = new java.util.concurrent.atomic.AtomicBoolean();
        when(fixture.statement.execute(anyString())).thenAnswer(ignored -> {
            created.set(true);
            return false;
        });
        when(fixture.exactQuery.executeQuery()).thenAnswer(ignored -> {
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(created.get(), false);
            when(rows.getString(1)).thenReturn("warehouse");
            when(rows.getString(2)).thenReturn("new_table");
            when(rows.getString(3)).thenReturn("BASE TABLE");
            when(rows.getString(4)).thenReturn("Doris");
            return rows;
        });
        when(fixture.database.queryRelationFingerprint(any(), anyString())).thenReturn("generation-1");
        var metadata = new cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata(List.of(), List.of("id"),
                cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance.unknown(),
                cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.value(true),
                cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.absent());
        when(fixture.database.readModelingSchema(any(), anyString(), anyString(), anyInt())).thenReturn(metadata);
    }

    private Fixture fixture() throws Exception {
        var connection = mock(Connection.class);
        var database = mock(AbstractDatabase.class);
        var statement = mock(Statement.class);
        var absentQuery = mock(java.sql.PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(absentQuery);
        when(absentQuery.executeQuery()).thenAnswer(ignored -> mock(ResultSet.class));
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.DORIS);
        when(statement.executeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            if (sql.equals("SHOW FRONTENDS")) {
                when(rows.getString("ClusterId")).thenReturn("123");
                when(rows.getString("Version")).thenReturn("doris-4.0.8");
                when(rows.getString("CurrentConnected")).thenReturn("Yes");
                when(rows.getBoolean("Alive")).thenReturn(true);
                when(rows.getBoolean("Join")).thenReturn(true);
            } else if (sql.equals("SHOW PROC '/dbs'")) {
                when(rows.getString("DbName")).thenReturn("warehouse");
                when(rows.getString("DbId")).thenReturn("456");
            } else if (sql.equals("SELECT @@sql_mode")) {
                when(rows.getString(1)).thenReturn("");
            } else if (sql.startsWith("SELECT CURRENT_CATALOG()")) {
                when(rows.getString(1)).thenReturn("internal");
                when(rows.getString(2)).thenReturn("root");
                when(rows.getInt(3)).thenReturn(0);
            } else if (sql.startsWith("SHOW CREATE TABLE ")) {
                when(rows.getString(2)).thenReturn("CREATE TABLE `new_table` (`id` bigint) UNIQUE KEY (`id`)");
            } else if (sql.startsWith("SELECT 1 FROM ")) {
                when(rows.next()).thenReturn(false);
            } else {
                throw new AssertionError("Unexpected metadata query: " + sql);
            }
            return rows;
        });
        String ddl = "CREATE TABLE `warehouse`.`new_table` (`id` bigint)";
        var plan = new SqlScriptPlan(ProductTypeEnum.DORIS, SqlScriptPlan.digest(ddl),
                List.of(new SqlScriptPlan.Step(1, ddl, SqlStatementClassifier.Kind.DDL)));
        when(database.planModelingSchemaScript(eq("warehouse"), eq("new_table"), anyList())).thenReturn(plan);
        return new Fixture(connection, database, statement, absentQuery, plan);
    }

    private record Fixture(Connection connection, AbstractDatabase database, Statement statement,
                           java.sql.PreparedStatement exactQuery, SqlScriptPlan plan) {
    }
}
