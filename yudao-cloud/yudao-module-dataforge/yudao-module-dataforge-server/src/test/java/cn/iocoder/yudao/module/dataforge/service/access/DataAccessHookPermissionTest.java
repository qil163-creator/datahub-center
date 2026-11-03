package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 脚本重写合同保持只读；当前新表同步的闭合入口拒绝所有生命周期脚本。 */
class DataAccessHookPermissionTest {
    @Test void queryHookHelperUsesItsOwnManagedDatasourceAndExecutionActor() {
        var f = new Fixture();
        assertEquals(sql("source_before") + "", f.authorize(10L, sql("source_before")));
        f.authorize(10L, sql("source_after")); f.authorize(20L, sql("target_before"));
        assertEquals(sql("target_after") + "", f.authorize(20L, sql("target_after")));
        verify(f.permissions).normalizeSelect(10L, sql("source_before"));
        verify(f.permissions).normalizeSelect(10L, sql("source_after"));
        verify(f.permissions).normalizeSelect(20L, sql("target_before"));
        verify(f.permissions).normalizeSelect(20L, sql("target_after"));
    }

    @ParameterizedTest
    @CsvSource({"10,source_before", "10,source_after", "20,target_before", "20,target_after"})
    void anyDeniedHookStopsAdmissionWithoutReplacingTheOriginalScripts(long datasource, String table) {
        var f = new Fixture();
        var denied = new IllegalStateException("Hook authorization denied");
        when(f.permissions.normalizeSelect(datasource, sql(table))).thenThrow(denied);
        assertSame(denied, assertThrows(IllegalStateException.class,
                () -> f.authorize(datasource, sql(table))));
        assertEquals(sql("source_before"), f.definition.getDataAccessJson().getSource().getFirst().getBeforeSqlScripts());
        assertEquals(sql("target_after"), f.definition.getDataAccessJson().getTarget().getAfterSqlScripts());
    }

    @Test void helperRewritesEveryStatementAndOnlyReturnsAuthorizedQueries() {
        var f = new Fixture();
        f.definition.getDataAccessJson().getSource().getFirst().setBeforeSqlScripts(
                sql("first_query") + ";\n" + sql("second_query"));
        String authorized = f.authorize(10L, f.definition.getDataAccessJson().getSource().getFirst().getBeforeSqlScripts());
        verify(f.permissions).normalizeSelect(10L, sql("first_query"));
        verify(f.permissions).normalizeSelect(10L, sql("second_query"));
        assertEquals(sql("first_query") + ";\n" + sql("second_query") + "",
                authorized);
    }

    @ParameterizedTest
    @CsvSource({"INSERT INTO fixture_rows VALUES (1)", "CREATE TABLE fixture_rows (id INT)"})
    void booleanValidationCannotAuthorizeMutationHooks(String script) {
        var f = new Fixture(); f.definition.getDataAccessJson().getTarget().setBeforeSqlScripts(script);
        assertThrows(IllegalStateException.class, () -> f.authorize(20L, script));
        verify(f.permissions, never()).normalizeSelect(20L, script);
    }

    @Test void providerCannotReplaceOneQueryWithMultipleOrWriteStatements() {
        for (String rewritten : List.of(sql("target_before") + "; " + sql("target_after"), "DELETE FROM fixture_rows", " ")) {
            var f = new Fixture();
            when(f.permissions.normalizeSelect(20L, sql("target_before"))).thenReturn(rewritten);
            assertThrows(RuntimeException.class, () -> f.authorize(20L, sql("target_before")));
        }
    }

    @Test void currentAuthorizedMigrationRejectsLifecycleHooksBeforeSourceAccess() {
        var f = new Fixture();
        f.definition.getDataAccessJson().getTarget().setWriteMode("CREATE_NEW");
        assertThrows(IllegalArgumentException.class, () -> f.executor.validateExecutionPermission(f.definition));
        verify(f.permissions, never()).requireExactTables(anyLong(), any(), anyString(), anyList());
        verify(f.permissions, never()).normalizeSelect(anyLong(), anyString());
    }

    private static String sql(String table) { return "SELECT * FROM \"fixture_scope\".\"" + table + "\""; }

    private static class Fixture {
        final DatabaseQuerySupport permissions = mock(DatabaseQuerySupport.class);
        final DataAccessExecutor executor = new DataAccessExecutor();
        final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
        final DataAccessDO definition;
        String authorize(Long database, String script) {
            return ReflectionTestUtils.invokeMethod(executor, "authorizeHook", database,
                    ProductTypeEnum.POSTGRESQL, script, 7L);
        }
        Fixture() {
            var source = new AccessTaskConfig.Source(); source.setSourceTableNames(List.of("fixture_rows"));
            source.setBeforeSqlScripts(sql("source_before")); source.setAfterSqlScripts(sql("source_after"));
            var config = new AccessTaskConfig(); config.setSource(List.of(source));
            config.getTarget().setBeforeSqlScripts(sql("target_before")); config.getTarget().setAfterSqlScripts(sql("target_after"));
            definition = DataAccessDO.builder().engineType(1).sourceDatabaseId(10L).targetDatabaseId(20L).dataAccessJson(config).build();
            definition.setCreator("7");
            var connection = new AccessDatabaseResolver.ConnectionInfo();
            connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setSchema("fixture_scope");
            when(permissions.normalizeSelect(anyLong(), anyString())).thenAnswer(invocation -> invocation.<String>getArgument(1));
            when(resolver.resolve(anyLong(), any())).thenReturn(connection);
            ReflectionTestUtils.setField(executor, "databaseQuerySupport", permissions);
            ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        }
    }
}

