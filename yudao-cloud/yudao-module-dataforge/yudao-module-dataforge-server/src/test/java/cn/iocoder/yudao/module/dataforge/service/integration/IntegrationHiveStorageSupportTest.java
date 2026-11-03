package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.HiveNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationHiveStorageSupportTest {
    @Test void strictNativeProfileEnrichesTypesAndAlwaysClosesItsOwnedConnection() throws Exception {
        var support = new IntegrationHiveStorageSupport(); var runtime = mock(DbSwitchRuntime.class); var connection = mock(Connection.class);
        ReflectionTestUtils.setField(support, "runtime", runtime);
        when(runtime.openConnection(eq(ProductTypeEnum.HIVE), isNull(), anyString(), anyString(), anyString())).thenReturn(connection);
        var table = new EtlNativeSqlCompiler.Table(List.of("spark_catalog", "scope", "source"), Map.of("amount", "DECIMAL"), ProductTypeEnum.HIVE, "scope", "source");
        try (var catalog = mockStatic(HiveNativeCatalog.class)) {
            var field = new ColumnDescription(); field.setFieldName("amount");
            var unknown = ColumnMetadataEvidence.unknown();
            field.setMetadataEvidence(new ColumnMetadataEvidence(unknown.provenance(), MetadataValue.value("decimal(20,3)"), unknown.defaultExpression(),
                    unknown.nullable(), unknown.numericPrecision(), unknown.numericScale(), unknown.characterLength(), unknown.identity(), unknown.generatedExpression(), unknown.charset(), unknown.collation()));
            catalog.when(() -> HiveNativeCatalog.read(connection, "scope", "source", 30)).thenReturn(List.of(field));
            assertEquals("decimal(20,3)", support.requireOrdinaryText(source(), table).columns().get("amount"));
        }
        verify(connection).close();
    }

    @Test void acidPartitionedUnknownFormatOrVersionCannotBorrowOrdinarySparkWrites() throws Exception {
        var support = new IntegrationHiveStorageSupport(); var runtime = mock(DbSwitchRuntime.class); var connection = mock(Connection.class);
        ReflectionTestUtils.setField(support, "runtime", runtime);
        when(runtime.openConnection(eq(ProductTypeEnum.HIVE), isNull(), anyString(), anyString(), anyString())).thenReturn(connection);
        var table = new EtlNativeSqlCompiler.Table(List.of("spark_catalog", "scope", "source"), Map.of("id", "BIGINT"), ProductTypeEnum.HIVE, "scope", "source");
        try (var catalog = mockStatic(HiveNativeCatalog.class)) {
            catalog.when(() -> HiveNativeCatalog.read(connection, "scope", "source", 30)).thenThrow(new SQLFeatureNotSupportedException("ACID"));
            var failure = assertThrows(IllegalArgumentException.class, () -> support.requireOrdinaryText(source(), table));
            assertTrue(failure.getMessage().contains("事务表"));
        }
        verify(connection).close();
    }
    private static EtlConnectionInfo source() {
        var info = new EtlConnectionInfo(); info.setProductType(ProductTypeEnum.HIVE); info.setSchema("scope");
        info.setJdbcUrl("jdbc:hive2://managed:10000/scope"); info.setUsername("fixture"); info.setPassword(""); return info;
    }
}
