package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditRuleContextRuntimeTest {
    @Test void independentContextsUseTheirOwnProductForGeneratedSql() {
        var first = context("FIRST_REGISTRY");
        var second = context("SECOND_REGISTRY");
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        assertEquals("FIRST_REGISTRY", generator.buildErrorDataSql(first, 12));
        assertEquals("SECOND_REGISTRY", generator.buildErrorDataSql(second, 12));
        assertEquals("FIRST_REGISTRY", generator.buildErrorDataSql(first, 12));
        verify(first.database(), times(2)).limitGeneratedSelect(anyString(), eq(12));
        verify(second.database()).limitGeneratedSelect(anyString(), eq(12));
    }

    @Test void aMissingProductInTheChosenRegistryCannotFallBackToGlobalDiscovery() {
        var registry = mock(DbSwitchProductRegistry.class);
        when(registry.metadata(ProductTypeEnum.ORACLE)).thenThrow(new UnsupportedOperationException("disabled"));
        var context = new AuditRuleContext(registry); context.setProductType(ProductTypeEnum.ORACLE);
        assertThrows(UnsupportedOperationException.class, context::database);
    }

    private AuditRuleContext context(String generatedSql) {
        var registry = mock(DbSwitchProductRegistry.class);
        var dialect = mock(AbstractDatabase.class);
        when(registry.metadata(ProductTypeEnum.ORACLE)).thenReturn(dialect);
        when(dialect.getDatabaseType()).thenReturn(ProductTypeEnum.ORACLE);
        when(dialect.limitGeneratedSelect(anyString(), anyInt())).thenReturn(generatedSql);
        var context = new AuditRuleContext(registry); context.setProductType(ProductTypeEnum.ORACLE);
        context.setTableName("orders"); context.setColumn("name"); context.setConfig(Map.of());
        return context;
    }
}
