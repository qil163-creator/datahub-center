package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.dataforge.service.security.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtlNodeAccessServiceTest {
    private final EtlNodeAccessService service = new EtlNodeAccessService();
    private final DatabaseQuerySupport queries = mock(DatabaseQuerySupport.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final DatabaseApi database = mock(DatabaseApi.class);
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "queries", queries);
        ReflectionTestUtils.setField(service, "databaseApi", database);
        ReflectionTestUtils.setField(service, "executionAccessService", access);
        var resolver = mock(EtlConnectionResolverAdapter.class);
        var connection = new EtlConnectionInfo(); connection.setSchema("actual"); connection.setProductType(ProductTypeEnum.POSTGRESQL);
        when(resolver.resolve(42L)).thenReturn(connection);
        ReflectionTestUtils.setField(service, "connectionResolverAdapter", resolver);
    }
    @Test void missingActorIsRejectedEvenWhenNoDatabaseIsReferenced() {
        assertThrows(RuntimeException.class, () -> service.authorize(List.of(), null, Map.of()));
        assertThrows(RuntimeException.class, () -> service.createReadPort(null));
        verifyNoInteractions(queries);
    }
    @Test void tableReadChecksDatabaseAndIgnoresStaleSqlFromAnotherMode() {
        service.authorize(List.of(node("read-db", "{\"databaseId\":42,\"tableName\":\"source\",\"ifSql\":0,\"sql\":\"select * from other.secret\"}")), 7L, Map.of());
        verify(access).requireDatabase(42L);
        verifyNoInteractions(queries);
    }
    @Test void managedTargetResolvesPhysicalRelationWithoutReadOnlySqlValidation() {
        var table = new DatabaseQueryTableRespDTO(); table.setSchemaName("target"); table.setTableName("rows"); table.setTableReference("\"target\".\"rows\"");
        when(database.resolveQueryTable(42L, "rows")).thenReturn(CommonResult.success(table));
        service.authorize(List.of(node("managed-db-output", "{\"databaseId\":42,\"tableName\":\"rows\"}")), 7L, Map.of());
        verify(database).resolveQueryTable(42L, "rows");
        verify(access).requireDatabase(42L);
        verifyNoInteractions(queries);
    }
    @Test void explicitSchemaAndQualifiedNamesRemainValidPhysicalIdentifiers() {
        service.authorize(List.of(node("read-db", "{\"databaseId\":42,\"tableName\":\"\\\"other\\\".\\\"Source.Rows\\\"\",\"schemaName\":\"other\"}")), 7L, Map.of());
        verify(access).requireDatabase(42L);
        verifyNoInteractions(queries);
    }
    @Test void readPortNormalizesSqlAndPreservesEveryOriginalRowAndField() {
        String sql = "select id, secret from source";
        String normalized = "SELECT id, secret FROM actual.source";
        when(queries.normalizeSelect(42L, sql)).thenReturn(normalized);
        var read = service.createReadPort(7L);
        assertEquals(normalized, read.rewrite("source", 42L, sql));
        List<Map<String, Object>> rows = List.of(Map.of("id", 1L, "secret", "original"));
        assertSame(rows, read.filter("source", 42L, normalized, rows));
        assertSame(rows, read.filter("source", 42L, normalized, rows, sql));
        verify(access).requireDatabase(42L);
        verify(queries).normalizeSelect(42L, sql);
        verifyNoMoreInteractions(queries);
    }
    private static EtlNodeDef node(String code, String config) {
        var node = new EtlNodeDef(); node.setNo("node"); node.setToolCode(code); node.setNodeJson(config); return node;
    }
}
