package cn.iocoder.yudao.module.dataforge.service.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApiImpl;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 复用真实表引用解析器，精确名称与只读语法不依赖治理数据策略。 */
class DatabaseQuerySupportTest {
    final DatabaseQuerySupport support = new DatabaseQuerySupport();
    final DatabaseApiImpl database = new DatabaseApiImpl();
    final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    @BeforeEach void setup() {
        var source = new AccessDatabaseResolver.ConnectionInfo(); source.setDatabaseId(10L);
        source.setProductType(ProductTypeEnum.POSTGRESQL); source.setSchema("source_scope");
        when(resolver.resolve(eq(10L), any())).thenReturn(source);
        ReflectionTestUtils.setField(database, "databaseResolver", resolver);
        ReflectionTestUtils.setField(support, "databaseApi", database);
    }
    @ParameterizedTest @ValueSource(strings={"a,b", "report?", "a*b", " leading", "trailing　", "quoted\"table"})
    void literalNamesRemainOneExactPhysicalTable(String name) {
        assertDoesNotThrow(() -> support.requireExactTables(10L, ProductTypeEnum.POSTGRESQL, "source_scope", List.of(name)));
    }
    @Test void missingManagedContextCannotBindAnObject() {
        assertThrows(IllegalArgumentException.class, () -> support.requireExactTables(10L, null, "source_scope", List.of("table")));
        assertThrows(IllegalArgumentException.class, () -> support.normalizeSelect(null, "SELECT 1"));
        verifyNoInteractions(resolver);
    }
    @ParameterizedTest @ValueSource(strings={"schema", "name", "reference", "sql"})
    void substitutedPhysicalBindingIsRejected(String changed) {
        var api = mock(DatabaseApi.class); ReflectionTestUtils.setField(support, "databaseApi", api);
        var wrong = new DatabaseQueryTableRespDTO(); wrong.setSchemaName("source_scope"); wrong.setTableName("a,b");
        wrong.setTableReference("\"source_scope\".\"a,b\""); wrong.setNormalizedSql("SELECT * FROM " + wrong.getTableReference());
        switch (changed) {
            case "schema" -> wrong.setSchemaName("other_scope");
            case "name" -> wrong.setTableName("other");
            case "reference" -> wrong.setTableReference("\"other_scope\".\"a,b\"");
            case "sql" -> wrong.setNormalizedSql("SELECT * FROM \"other_scope\".\"a,b\"");
        }
        when(api.resolveQueryTable(anyLong(), anyString())).thenReturn(CommonResult.success(wrong));
        assertThrows(IllegalStateException.class, () -> support.requireExactTables(10L, ProductTypeEnum.POSTGRESQL,
                "source_scope", List.of("a,b")));
    }
    @ParameterizedTest @ValueSource(strings={"DELETE FROM rows", "CREATE TABLE rows(id int)", "SELECT * FROM rows; SELECT * FROM rows", " "})
    void nonQueryOrMultipleStatementsRemainRejected(String sql) {
        assertThrows(RuntimeException.class, () -> support.normalizeSelect(10L, sql));
    }
}
