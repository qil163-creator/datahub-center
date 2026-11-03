package cn.iocoder.yudao.framework.dbswitch.data.handler;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByDatasourceService;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationTargetIdentityTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void caseVariantFollowsTheActualQuotedIdentifierContract(boolean caseSensitive) throws Exception {
        var source = mock(HikariDataSource.class);
        var target = mock(HikariDataSource.class);
        var connection = mock(Connection.class);
        var facts = mock(DatabaseMetaData.class);
        when(target.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(facts);
        when(facts.supportsMixedCaseQuotedIdentifiers()).thenReturn(caseSensitive);
        var metadata = metadata(target, "Orders");
        try (var runtime = DbSwitchRuntime.standalone()) {
            Boolean exists = ReflectionTestUtils.invokeMethod(handler(source, target, runtime), "targetTableExists", metadata);
            assertEquals(!caseSensitive, exists);
        }
        verify(connection).close();
    }

    @Test void metadataFailureCannotAuthorizeUsingACaseVariant() throws Exception {
        var target = mock(HikariDataSource.class);
        when(target.getConnection()).thenThrow(new SQLException("private connection detail"));
        var metadata = metadata(target, "Orders");
        try (var runtime = DbSwitchRuntime.standalone()) {
            var handler = handler(mock(HikariDataSource.class), target, runtime);
            var failure = assertThrows(IllegalStateException.class,
                    () -> ReflectionTestUtils.invokeMethod(handler, "targetTableExists", metadata));
            assertFalse(failure.getMessage().contains("private connection detail"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"orders", "different"})
    void exactPresenceOrAbsenceNeedsNoCaseProbe(String actual) {
        var target = mock(HikariDataSource.class);
        var metadata = metadata(target, actual);
        try (var runtime = DbSwitchRuntime.standalone()) {
            Boolean exists = ReflectionTestUtils.invokeMethod(handler(mock(HikariDataSource.class), target, runtime),
                    "targetTableExists", metadata);
            assertEquals("orders".equals(actual), exists);
        }
        verifyNoInteractions(target);
    }

    private static IMetaDataByDatasourceService metadata(DataSource source, String name) {
        var metadata = mock(IMetaDataByDatasourceService.class);
        var table = new TableDescription(); table.setSchemaName("public"); table.setTableName(name);
        when(metadata.queryTableList("public")).thenReturn(List.of(table));
        when(metadata.getDataSource()).thenReturn(source);
        return metadata;
    }

    private static MigrationHandler handler(HikariDataSource source, HikariDataSource target, DbSwitchRuntime runtime) {
        var request = new MigrationRequest();
        var input = new SourceDataSourceProperties(); input.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        input.setSourceSchema("public"); request.getSource().add(input);
        request.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        request.getTarget().setTargetSchema("public"); request.getTarget().setTargetTable("orders");
        var table = new TableDescription(); table.setSchemaName("public"); table.setTableName("orders");
        return MigrationHandler.createInstance(table, request, 0, source, target, null, runtime);
    }
}
