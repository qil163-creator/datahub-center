package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuditClickhouseDialectTest {
    @Test void nullRuleDelegatesNativeTextConversionAndNeverComparesNumberWithEmptyString() {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(ProductTypeEnum.CLICKHOUSE);
        context.setTableName("sample"); context.setColumn("amount"); context.setConfig(Map.of());
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        assertTrue(generator.buildCountSql(context).contains("toString(`amount`) <> ''"));
        assertTrue(generator.buildGroupedKeyCountSql(context, "org_code", "id").contains("toString(`amount`) <> ''"));
        context.setConfig(Map.of("allowBlank", true));
        assertFalse(generator.buildCountSql(context).contains("toString"));
        assertThrows(IllegalArgumentException.class, () -> AuditSqlDialect.castText(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.CLICKHOUSE), "amount; SELECT 1"));
    }
}
