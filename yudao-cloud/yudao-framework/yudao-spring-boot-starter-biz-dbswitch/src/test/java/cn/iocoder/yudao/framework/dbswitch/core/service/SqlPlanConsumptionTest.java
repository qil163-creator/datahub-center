package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlPlanConsumptionTest {
    @Test void authorizedPlanIsExecutedVerbatimAndBindingsKeepGlobalOrderIncludingNull() throws Exception {
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,
                "insert into example(value) values (?); update example set value=? where id=?;");
        Connection connection = mock(Connection.class);
        PreparedStatement first = mock(PreparedStatement.class), second = mock(PreparedStatement.class);
        when(connection.prepareStatement(plan.steps().get(0).sql())).thenReturn(first);
        when(connection.prepareStatement(plan.steps().get(1).sql())).thenReturn(second);
        var ordinals = new java.util.ArrayList<Integer>();

        var result = database().executePlan(connection, plan, Arrays.asList("left;right", null, 42), 1, null, ordinals::add);

        assertTrue(result.getSuccess());
        assertEquals(List.of(1, 2), ordinals);
        assertEquals(plan.steps().stream().map(SqlScriptPlan.Step::sql).toList(),
                result.getResults().stream().map(item -> item.getSql()).toList());
        verify(first).setObject(1, "left;right");
        verify(second).setObject(1, null);
        verify(second).setObject(2, 42);
        verify(first).setQueryTimeout(30);
        verify(first).execute();
        verify(second).execute();
        verify(connection, never()).commit();
        verify(connection, never()).close();
    }

    @Test void bindingOrProductMismatchFailsBeforeTouchingConnection() {
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "insert into example(value) values (?)");
        Connection connection = mock(Connection.class);
        assertThrows(IllegalArgumentException.class, () -> database().executePlan(connection, plan, List.of(), 1, null, ignored -> { }));
        var foreign = SqlScriptPlan.parse(ProductTypeEnum.MYSQL, "delete from example");
        assertThrows(IllegalArgumentException.class, () -> database().executePlan(connection, foreign, List.of(), 1, null, ignored -> { }));
        verifyNoInteractions(connection);
    }

    @Test void cancellationBetweenStepsCannotExecuteTheNextStatement() throws Exception {
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "delete from first_table; delete from second_table");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        assertThrows(IllegalStateException.class, () -> database().executePlan(connection, plan, List.of(), 1, null,
                ordinal -> { if (ordinal == 2) throw new IllegalStateException("stopped"); }));
        verify(statement).execute(plan.steps().getFirst().sql());
        verify(statement, never()).execute(plan.steps().getLast().sql());
        verify(connection, never()).commit();
    }

    @Test void cteClassificationTraversesBodiesAndLocksWithoutMatchingLiteralText() {
        assertEquals(SqlStatementClassifier.Kind.QUERY, classify("with sample as (select ' for update into ' as value) select * from sample"));
        // Druid does not accept this RETURNING projection; reject instead of claiming it is read-only.
        assertThrows(IllegalArgumentException.class, () -> classify("with changed as (delete from example returning id) select * from changed"));
        assertEquals(SqlStatementClassifier.Kind.DML, classify("with changed as (delete from example returning *) select * from changed"));
        assertEquals(SqlStatementClassifier.Kind.DML, classify("with changed as (update example set value=1 returning id) select * from changed"));
        assertEquals(SqlStatementClassifier.Kind.DML, classify("with locked as (select * from example for update) select * from locked"));
        assertThrows(IllegalArgumentException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.POSTGRESQL,
                "with changed as (delete from example returning id) select * from changed"));
    }

    private static SqlStatementClassifier.Kind classify(String sql) {
        return SqlStatementClassifier.classify(ProductTypeEnum.POSTGRESQL, sql).kind();
    }
    private static AbstractDatabase database() {
        var database = mock(AbstractDatabase.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.POSTGRESQL);
        return database;
    }
}
