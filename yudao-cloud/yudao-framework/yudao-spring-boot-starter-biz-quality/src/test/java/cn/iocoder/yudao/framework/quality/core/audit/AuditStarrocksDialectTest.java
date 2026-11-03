package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuditStarrocksDialectTest {
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"STARROCKS", "DORIS"})
    void nonBlankRuleUsesNativeTextConversionWithoutAcceptingExpressions(ProductTypeEnum product) {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(product);
        context.setTableName("sample"); context.setColumn("amount"); context.setConfig(Map.of("allowBlank", false));
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        assertTrue(generator.buildCountSql(context).contains("CAST(`amount` AS STRING) <> ''"));
        assertTrue(generator.buildGroupedKeyCountSql(context, "org_code", "id").contains("CAST(`amount` AS STRING) <> ''"));
        context.setConfig(Map.of("allowBlank", true));
        assertFalse(generator.buildCountSql(context).contains("CAST"));
        assertThrows(IllegalArgumentException.class, () -> AuditSqlDialect.castText(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(product), "amount; SELECT 1"));
    }
}
