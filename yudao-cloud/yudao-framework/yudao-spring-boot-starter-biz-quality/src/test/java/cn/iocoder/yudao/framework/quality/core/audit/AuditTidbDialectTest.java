package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuditTidbDialectTest {
    @Test void nonBlankRuleUsesTextRatherThanNumericEmptyStringCoercion() {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(ProductTypeEnum.TIDB);
        context.setTableName("sample"); context.setColumn("amount"); context.setConfig(Map.of("allowBlank", false));
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        assertTrue(generator.buildCountSql(context).contains("CAST(`amount` AS CHAR) <> ''"));
        assertTrue(generator.buildGroupedKeyCountSql(context, "org_code", "id").contains("CAST(`amount` AS CHAR) <> ''"));
        context.setConfig(Map.of("allowBlank", true));
        assertFalse(generator.buildCountSql(context).contains("CAST"));
        // 入参是物理列名：任何名称都整体作为一个反引号标识符引用（内嵌反引号加倍），不会成为可执行 SQL；空名、NUL 与超长名被拒绝。
        var tidb = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TIDB);
        assertEquals("CAST(`amount; SELECT 1` AS CHAR)", AuditSqlDialect.castText(tidb, "amount; SELECT 1"));
        assertEquals("CAST(`a``b` AS CHAR)", AuditSqlDialect.castText(tidb, "a`b"));
        assertThrows(IllegalArgumentException.class, () -> AuditSqlDialect.castText(tidb, "a\0b"));
        assertThrows(IllegalArgumentException.class, () -> AuditSqlDialect.castText(tidb, "x".repeat(65)));
    }
}

