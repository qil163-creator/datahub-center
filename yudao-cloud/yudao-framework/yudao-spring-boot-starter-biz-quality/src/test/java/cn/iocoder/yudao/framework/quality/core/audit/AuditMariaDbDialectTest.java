package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuditMariaDbDialectTest {
    @Test void numericZeroAndPadSpaceDoNotBorrowEmptyStringComparison() {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()); context.setProductType(ProductTypeEnum.MARIADB);
        context.setTableName("sample"); context.setColumn("amount"); context.setConfig(Map.of());
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        for (String sql : new String[]{generator.buildCountSql(context), generator.buildErrorDataSql(context, 50),
                generator.buildGroupedKeyCountSql(context, "org_code", "id")}) {
            assertTrue(sql.contains("CHAR_LENGTH(CAST(`amount` AS CHAR)) > 0"), sql);
            assertFalse(sql.contains("<> ''"));
        }
        context.setConfig(Map.of("allowBlank", true));
        assertFalse(generator.buildCountSql(context).contains("CAST"));
    }

    @Test void textConversionAcceptsOnlyOnePhysicalIdentifier() {
        assertEquals("CAST(`amount` AS CHAR)", AuditSqlDialect.castText(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MARIADB), "amount"));
        assertEquals("CAST(`amount; SELECT 1` AS CHAR)", AuditSqlDialect.castText(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MARIADB), "amount; SELECT 1"));
        assertEquals("CAST(`x``y` AS CHAR)", AuditSqlDialect.castText(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MARIADB), "x`y"));
        assertThrows(IllegalArgumentException.class, () -> AuditSqlDialect.castText(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MARIADB), "x\0y"));
    }
}
