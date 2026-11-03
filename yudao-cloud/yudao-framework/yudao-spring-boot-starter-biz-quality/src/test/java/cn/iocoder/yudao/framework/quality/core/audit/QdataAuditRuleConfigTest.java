package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 稽查配置边界与集合检测范围的回归验证。 */
class QdataAuditRuleConfigTest {

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidRanges")
    void invalidRangesFailBeforeSqlExecution(String strategy, Map<String, Object> config) {
        AuditRuleContext context = context(config);
        AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator(strategy);
        assertThrows(IllegalArgumentException.class, () -> generator.buildCountSql(context));
        assertThrows(IllegalArgumentException.class, () -> generator.buildErrorDataSql(context, 10));
        assertThrows(IllegalArgumentException.class, () -> generator.buildNormalDataSql(context, 10));
    }

    static Stream<Arguments> invalidRanges() {
        return Stream.of(
                Arguments.of("LENGTH_VALIDATION", Map.of("minLength", -1)),
                Arguments.of("LENGTH_VALIDATION", Map.of("maxLength", -1)),
                Arguments.of("LENGTH_VALIDATION", Map.of("minLength", 5, "maxLength", 2)),
                Arguments.of("DECIMAL_PRECISION_VALIDATION", Map.of("scale", -1)),
                Arguments.of("DECIMAL_PRECISION_VALIDATION", Map.of("precision", 0, "scale", 0)),
                Arguments.of("DECIMAL_PRECISION_VALIDATION", Map.of("precision", 2, "scale", 3)),
                Arguments.of("NUMERIC_RANGE_VALIDATION", Map.of("minValue", "2", "maxValue", "1")),
                Arguments.of("NUMERIC_RANGE_VALIDATION", Map.of(
                        "minValue", "9007199254740993.0000000000000000002",
                        "maxValue", "9007199254740993.0000000000000000001")));
    }

    @Test
    void zeroLengthAndEqualNumericBoundsRemainValidConfigurations() {
        AuditSqlRegistry registry = new AuditSqlRegistry();
        assertDoesNotThrow(() -> registry.getGenerator("LENGTH_VALIDATION")
                .buildCountSql(context(Map.of("minLength", 0, "maxLength", 0))));
        assertDoesNotThrow(() -> registry.getGenerator("DECIMAL_PRECISION_VALIDATION")
                .buildCountSql(context(Map.of("scale", 0, "skipInteger", true))));
        assertDoesNotThrow(() -> registry.getGenerator("NUMERIC_RANGE_VALIDATION")
                .buildCountSql(context(Map.of("minValue", "1.00", "maxValue", "1"))));
    }

    @Test
    void compositeUniquenessKeepsTypedScopeInEveryPopulationScan() {
        AuditRuleContext context = context(Map.of("ignoreNull", false));
        context.setColumns(List.of("a", "b"));
        context.setWhereClause("active = true OR id = 90");
        context.addTextEqualsScope("population", "inside");
        context.addTextInScope("phase", List.of("ready", "approved"));
        AuditSqlGenerator generator = new AuditSqlRegistry()
                .getGenerator("COMPOSITE_UNIQUENESS_VALIDATION");
        for (String sql : List.of(generator.buildCountSql(context),
                generator.buildErrorDataSql(context, 100), generator.buildNormalDataSql(context, 100))) {
            assertEquals(2, occurrences(sql, "\"population\" = 'inside'"), sql);
            assertEquals(2, occurrences(sql, "\"phase\" IN ('ready', 'approved')"), sql);
            assertTrue(sql.contains("(active = true OR id = 90)"), sql);
        }
    }

    private static int occurrences(String text, String fragment) {
        return (text.length() - text.replace(fragment, "").length()) / fragment.length();
    }

    private static AuditRuleContext context(Map<String, Object> config) {
        AuditRuleContext context = new AuditRuleContext(DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL);
        context.setTableName("qdata_audit_fixture");
        context.setColumn("value");
        context.setConfig(config);
        return context;
    }
}

