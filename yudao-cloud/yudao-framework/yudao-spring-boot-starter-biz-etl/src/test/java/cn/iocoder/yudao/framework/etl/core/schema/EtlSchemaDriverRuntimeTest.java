package cn.iocoder.yudao.framework.etl.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtlSchemaDriverRuntimeTest {
    @Test void tableAndSqlPreviewUseTheSelectedProfile() {
        for (boolean sql : List.of(false, true)) {
            var runtime = mock(DbSwitchRuntime.class);
            var metadata = mock(MetaDataByJdbcServiceImpl.class);
            var conn = connection();
            when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL, "selected-profile")).thenReturn(metadata);
            var column = new ColumnDescription(); column.setFieldName("amount"); column.setFieldTypeName("numeric");
            when(metadata.queryTableSchema("jdbc:test", "test", "", "public", "example", 10_000))
                    .thenReturn(new TableSchemaMetadata(List.of(column), List.of()));
            when(metadata.querySqlColumnMeta("jdbc:test", "test", "", "select amount from example"))
                    .thenReturn(List.of(column));
            var result = new EtlSchemaService(runtime).resolve(dag(sql), ignored -> conn, null);
            assertTrue(result.getErrors().isEmpty());
            assertEquals("amount", result.getNodes().get("read").getOutputFields().getFirst().getName());
            verify(runtime).jdbcMetadata(ProductTypeEnum.POSTGRESQL, "selected-profile");
            verify(runtime, never()).jdbcMetadata(ProductTypeEnum.POSTGRESQL);
        }
    }

    @Test void rejectedProfileDoesNotFallBackToDefaultMetadata() {
        var runtime = mock(DbSwitchRuntime.class);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL, "selected-profile"))
                .thenThrow(new IllegalArgumentException("Unknown driver profile"));
        var result = new EtlSchemaService(runtime).resolve(dag(false), ignored -> connection(), null);
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getNodes().get("read").getOutputFields().isEmpty());
        verify(runtime).jdbcMetadata(ProductTypeEnum.POSTGRESQL, "selected-profile");
        verifyNoMoreInteractions(runtime);
    }

    @Test void vendorFailureDoesNotExposeSqlOrConnectionDetailsInDesignErrors() {
        for (boolean sql : List.of(false, true)) {
            var runtime = mock(DbSwitchRuntime.class);
            var metadata = mock(MetaDataByJdbcServiceImpl.class);
            when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL, "selected-profile")).thenReturn(metadata);
            var failure = new IllegalStateException("private-query jdbc:secret password=fixture-only");
            if (sql) when(metadata.querySqlColumnMeta(anyString(), anyString(), anyString(), anyString())).thenThrow(failure);
            else when(metadata.queryTableSchema(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt())).thenThrow(failure);

            var result = new EtlSchemaService(runtime).resolve(dag(sql), ignored -> connection(), null);

            assertEquals(1, result.getErrors().size());
            assertTrue(result.getErrors().getFirst().contains("数据库字段元数据读取失败"));
            assertFalse(result.getErrors().toString().contains("private-query"));
            assertFalse(result.getErrors().toString().contains("jdbc:secret"));
            assertFalse(result.getErrors().toString().contains("password="));
            assertTrue(result.getNodes().get("read").getOutputFields().isEmpty());
        }
    }

    private static EtlConnectionInfo connection() {
        var conn = new EtlConnectionInfo(); conn.setDatabaseId(1L);
        conn.setProductType(ProductTypeEnum.POSTGRESQL); conn.setDriverProfileId("selected-profile");
        conn.setJdbcUrl("jdbc:test"); conn.setUsername("test"); conn.setPassword(""); conn.setSchema("public");
        return conn;
    }

    private static EtlDag dag(boolean sql) {
        var node = new EtlNodeDef(); node.setNo("read"); node.setName("read");
        node.setToolCode("read-db"); node.setToolType(EtlNodeDef.TOOL_TYPE_INPUT);
        node.setNodeJson(sql ? "{\"databaseId\":1,\"ifSql\":1,\"sql\":\"select amount from example\"}"
                : "{\"databaseId\":1,\"tableName\":\"example\"}");
        return EtlDag.build(List.of(node), List.of());
    }
}
