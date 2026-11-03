package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.enums.push.PushTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataPushPermissionSqlTest {
    @Test void watermarkKeepsObservedJdbcTypeAndRejectsMissingOrAmbiguousProjection() {
        var column = new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription();
        column.setFieldName("revision"); column.setFieldType(java.sql.Types.BIGINT);
        var bound = DataPushExecutor.projectedWatermark(List.of(column), "revision");
        assertEquals(java.sql.Types.BIGINT, bound.getFieldType());
        assertNotSame(column, bound);
        assertThrows(IllegalStateException.class, () -> DataPushExecutor.projectedWatermark(List.of(column), "hidden"));
        assertThrows(IllegalStateException.class, () -> DataPushExecutor.projectedWatermark(List.of(column, column), "revision"));
        column.setFieldType(java.sql.Types.NULL);
        assertThrows(IllegalStateException.class, () -> DataPushExecutor.projectedWatermark(List.of(column), "revision"));
    }
    final DataPushExecutor executor = new DataPushExecutor();
    final DatabaseQuerySupport queries = mock(DatabaseQuerySupport.class);
    final ExecutionAccessService access = mock(ExecutionAccessService.class);
    final DataPushChannel channel = mock(DataPushChannel.class);
    final DataPushTaskDO task = new DataPushTaskDO();
    final AccessDatabaseResolver.ConnectionInfo source = new AccessDatabaseResolver.ConnectionInfo();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(executor, "databaseQuerySupport", queries);
        ReflectionTestUtils.setField(executor, "executionAccessService", access);
        task.setSourceDatabaseId(10L);
        task.setPushType(PushTypeEnum.SQL.getType());
        source.setProductType(ProductTypeEnum.POSTGRESQL);
        source.setSchema("Gov.Schema");
    }

    @Test void normalizedQuerySendsEveryRawRowAndField() {
        String original = "SELECT id FROM public.orders WHERE id>0";
        String executed = "SELECT id FROM public.orders WHERE id > 0";
        task.setSqlText(original);
        when(queries.normalizeSelect(10L, original)).thenReturn(executed);
        Object proof = ReflectionTestUtils.invokeMethod(executor, "preparePushQuerySql", task, source, 20L);
        List<Map<String, Object>> incoming = List.of(Map.of("id", 1, "value", "raw"), Map.of("id", 2, "value", "unchanged"));
        Integer count = ReflectionTestUtils.invokeMethod(executor, "flushPushBatch", task, channel,
                "Orders", incoming, "{}", new StringBuilder(), proof);
        assertEquals(2, count);
        var payload = ArgumentCaptor.forClass(PushPayload.class);
        verify(channel).push(payload.capture(), eq("{}"), any());
        assertEquals(incoming, payload.getValue().getDataList());
        assertEquals("Orders", payload.getValue().getObjectName());
    }

    @Test void tablePushIsQualifiedWithoutFoldingPhysicalCaseOrEmbeddedDots() {
        task.setPushType(PushTypeEnum.TABLE.getType());
        task.setTableName("Orders.Part");
        String original = "SELECT * FROM \"Gov.Schema\".\"Orders.Part\"";
        when(queries.normalizeSelect(10L, original)).thenReturn("SELECT id FROM \"Gov.Schema\".\"Orders.Part\"");
        assertNotNull(ReflectionTestUtils.invokeMethod(executor, "preparePushQuerySql", task, source, 20L));
        verify(queries).normalizeSelect(10L, original);
    }

    @Test void runChecksTheSavedActorOperationPermissionBeforeSending() {
        task.setCreator("20");
        String original = "select id from public.orders";
        String normalized = "SELECT id FROM public.orders";
        task.setSqlText(original);
        when(queries.normalizeSelect(10L, original)).thenReturn(normalized);
        ReflectionTestUtils.invokeMethod(executor, "assertPushPermission", task);
        assertEquals(normalized, task.getSqlText());
        var order = inOrder(access, queries);
        order.verify(access).requireUserPermission(20L, "dataforge:data-push:run");
        order.verify(queries).normalizeSelect(10L, original);
        verify(access).requireUserPermission(20L, "dataforge:data-push:run");
        doThrow(new IllegalStateException("operation denied")).when(access)
                .requireUserPermission(20L, "dataforge:data-push:run");
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(executor,
                "assertPushPermission", task));
        verify(queries, times(1)).normalizeSelect(anyLong(), anyString());
        verifyNoInteractions(channel);
    }

    @Test void invalidRunQueryFailsBeforeAnyChannelCall() {
        task.setCreator("20"); task.setSqlText("DELETE FROM public.orders");
        when(queries.normalizeSelect(10L, task.getSqlText())).thenThrow(new IllegalArgumentException("read only"));
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(executor,
                "assertPushPermission", task));
        verifyNoInteractions(channel);
    }

    @Test void missingNormalizationDoesNotExecuteOrSendOriginalSql() {
        task.setSqlText("SELECT id FROM public.orders");
        when(queries.normalizeSelect(anyLong(), anyString())).thenReturn("");
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(executor,
                "preparePushQuerySql", task, source, 20L));
        verifyNoInteractions(channel);
    }
}
