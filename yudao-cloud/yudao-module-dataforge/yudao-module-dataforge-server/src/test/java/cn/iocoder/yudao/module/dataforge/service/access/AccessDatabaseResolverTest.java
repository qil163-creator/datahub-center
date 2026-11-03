package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApiImpl;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_TARGET_DATABASE_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_NAMESPACE_INVALID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 从平台数据源记录到所有同步/元数据调用方的公共连接契约。 */
class AccessDatabaseResolverTest {

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MYSQL", "MARIADB", "TIDB", "DORIS", "STARROCKS"})
    void catalogProductsResolveConfiguredDatabaseWhenSchemaIsAbsent(ProductTypeEnum product) {
        var database = database();
        var resolver = resolver(database, product);

        var resolved = resolver.resolve(database.getId(), DATA_ACCESS_TARGET_DATABASE_NOT_EXISTS);

        assertEquals("Business_DB", resolved.getSchema());
        assertEquals("Business_DB", resolved.getDatabaseName());
        assertEquals(product, resolved.getProductType());
        assertEquals(database.getId(), resolved.getDatabaseId());
        assertEquals(database.getUsageType(), resolved.getUsageType());
        assertNull(database.getDatabaseSchema()); // 运行时适配，不回写用户的数据源配置。
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void mysqlTreatsMissingSchemaConsistently(String schema) {
        var database = database();
        database.setDatabaseSchema(schema);
        assertEquals("Business_DB", resolver(database, ProductTypeEnum.MYSQL).resolve(database).getSchema());
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "SQLSERVER", "ORACLE", "DM"})
    void schemaProductsNeverSubstituteTheirConnectionDatabase(ProductTypeEnum product) {
        var database = database();
        var resolver = resolver(database, product);
        assertEquals("", resolver.resolve(database).getSchema());

        database.setDatabaseSchema("Exact Schema");
        assertEquals("Exact Schema", resolver.resolve(database).getSchema());
    }

    @Test
    void existingExplicitNamespaceKeepsExactSpelling() {
        var database = database();
        database.setDatabaseSchema(" Existing_DB ");
        assertEquals(" Existing_DB ", resolver(database, ProductTypeEnum.DORIS).resolve(database).getSchema());
    }

    @Test
    void missingDatabaseDoesNotInventNamespaceFromConnectionUrl() {
        var database = database();
        database.setDatabaseName(null);
        database.setJdbcUrl("jdbc:mysql://localhost/connection_default");
        assertEquals("", resolver(database, ProductTypeEnum.MYSQL).resolve(database).getSchema());
    }

    @Test
    void malformedNamespaceReturnsSafeConfigurationError() {
        var database = database();
        database.setDatabaseName("invalid\0name");
        var error = assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> resolver(database, ProductTypeEnum.MYSQL).resolve(database));
        assertEquals(DATABASE_NAMESPACE_INVALID.getCode(), error.getCode());
        assertEquals(DATABASE_NAMESPACE_INVALID.getMsg(), error.getMessage());
        assertFalse(error.getMessage().contains("invalid\0name"));
    }

    @Test
    void connectionApiExposesSameEffectiveNamespaceUsedBySync() {
        var database = database();
        var api = new DatabaseApiImpl();
        ReflectionTestUtils.setField(api, "databaseResolver", resolver(database, ProductTypeEnum.MYSQL));

        var connection = api.getDatabaseConnection(database.getId()).getCheckedData();

        assertEquals("Business_DB", connection.getSchema());
        assertEquals("MYSQL", connection.getProductType());
        assertEquals(database.getId(), connection.getId());
    }

    private static DatabaseDO database() {
        return DatabaseDO.builder().id(9L).name("test source").usageType(1)
                .databaseName("Business_DB").build();
    }

    private static AccessDatabaseResolver resolver(DatabaseDO database, ProductTypeEnum product) {
        var resolver = spy(new AccessDatabaseResolver());
        doReturn(product).when(resolver).resolveProductType(database);
        var mapper = mock(DatabaseMapper.class);
        when(mapper.selectById(database.getId())).thenReturn(database);
        ReflectionTestUtils.setField(resolver, "databaseMapper", mapper);
        return resolver;
    }
}
