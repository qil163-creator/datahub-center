package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 新规则参数错误在 SQL 执行之前被拒绝。 */
class QdataCompleteAuditConfigTest {
    private final AuditSqlRegistry registry = new AuditSqlRegistry();

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidConfigs")
    void rejectsAmbiguousAndUnsafeConfig(String strategy, Map<String, Object> config) {
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig(strategy, config));
    }
    static Stream<Arguments> invalidConfigs() {
        return Stream.of(
                Arguments.of("NUMERIC_RELATION_VALIDATION", Map.of("conditions", List.of(Map.of("leftField", "a", "rightField", "b", "operator", ";drop")))),
                Arguments.of("NUMERIC_RELATION_VALIDATION", Map.of("conditions", List.of())),
                Arguments.of("CONDITIONAL_REQUIRED_VALIDATION", Map.of("conditionColumn", "state", "conditionValues", List.of(), "requiredColumns", List.of("name"))),
                Arguments.of("RECORD_COMPLETENESS_VALIDATION", Map.of("blankAsNull", "false")),
                Arguments.of("RECORD_COMPLETENESS_VALIDATION", Collections.singletonMap("blankAsNull", null)),
                Arguments.of("SENSITIVE_MASK_VALIDATION", Map.of("maskChar", "**")),
                Arguments.of("SENSITIVE_MASK_VALIDATION", Map.of("maskType", "CUSTOM", "regex", "[")),
                Arguments.of("SENSITIVE_MASK_VALIDATION", Map.of("maskType", "PHONE", "regex", ".*")),
                Arguments.of("NUMERIC_OUTLIER_VALIDATION", Map.of()),
                Arguments.of("NUMERIC_OUTLIER_VALIDATION", Map.of("minValue", "2", "maxValue", "1")),
                Arguments.of("NUMERIC_OUTLIER_VALIDATION", Map.of("minValue", 1.2)),
                Arguments.of("NUMERIC_OUTLIER_VALIDATION", Map.of("method", "IQR", "multiplier", "0")),
                Arguments.of("NUMERIC_OUTLIER_VALIDATION", Map.of("method", "IQR", "multiplier", "100.1")),
                Arguments.of("NUMERIC_OUTLIER_VALIDATION", Map.of("method", "IQR", "minSamples", 3)),
                Arguments.of("NUMERIC_OUTLIER_VALIDATION", Map.of("method", "IQR", "minSamples", 4.5)),
                Arguments.of("NUMERIC_OUTLIER_VALIDATION", Map.of("method", "IQR", "values", List.of("1"))),
                Arguments.of("NUMERIC_OUTLIER_VALIDATION", Map.of("method", "INVALID_VALUES", "values", List.of("1E100"))),
                Arguments.of("STATE_CONSISTENCY_VALIDATION", state(Map.of("column", "amount", "operator", "EQ", "valueType", "DECIMAL", "value", 0.1))),
                Arguments.of("STATE_CONSISTENCY_VALIDATION", state(Map.of("column", "amount", "operator", "IS_NULL", "value", "1"))),
                Arguments.of("STATE_CONSISTENCY_VALIDATION", state(Map.of("column", "amount", "operator", "IN", "values", List.of()))),
                Arguments.of("DATE_FORMAT_VALIDATION", Map.of("pattern", "dd-MM-yyyy")),
                Arguments.of("DATE_FORMAT_VALIDATION", Map.of("pattern", "yyyy-MM-dd", "regex", "[")),
                Arguments.of("MODEL_STRUCTURE_VALIDATION", Map.of("expectedColumns", List.of(Map.of("name", "id")), "actualColumns", List.of(Map.of("name", "id")))),
                Arguments.of("MODEL_STRUCTURE_VALIDATION", Map.of("expectedColumns", List.of(Map.of("name", "id"), Map.of("name", "id")))),
                Arguments.of("MODEL_STRUCTURE_VALIDATION", Map.of("expectedColumns", List.of(Map.of("name", "id", "precision", 2, "scale", 3)))),
                Arguments.of("MODEL_STRUCTURE_VALIDATION", Map.of("expectedColumns", List.of(nullableColumn()))),
                Arguments.of("COLUMN_METADATA_COMPLETENESS", Map.of("requiredProperties", List.of("unknown"))),
                Arguments.of("COLUMN_METADATA_COMPLETENESS", Map.of("requiredProperties", List.of("name", "name"))),
                Arguments.of("CROSS_TABLE_CONSISTENCY", Map.of("comparisons", List.of()))
        );
    }
    private static Map<String, Object> nullableColumn() {
        Map<String, Object> column = new LinkedHashMap<>(); column.put("name", "id"); column.put("nullable", null); return column;
    }
    private static Map<String, Object> state(Map<String, Object> assertion) {
        return Map.of("conditionColumn", "state", "conditionValues", List.of("PAID"), "assertions", List.of(assertion));
    }

    @Test
    void newRuleFieldsCannotEscapeBoundColumns() {
        AuditRuleContext context = context();
        context.setConfig(Map.of("conditionColumn", "state", "conditionValues", List.of("A"), "requiredColumns", List.of("amount;drop table x")));
        assertThrows(IllegalArgumentException.class, () -> registry.getGenerator("CONDITIONAL_REQUIRED_VALIDATION").validateConfig(context));
        context.setConfig(Map.of("conditions", List.of(Map.of("leftField", "amount", "rightField", "unbound", "operator", "<="))));
        assertThrows(IllegalArgumentException.class, () -> registry.getGenerator("NUMERIC_RELATION_VALIDATION").validateConfig(context));
    }

    @Test
    void uniquenessValidationUsesItsSetQueryRatherThanARowPredicate() {
        AuditRuleContext context = context(); context.setConfig(Map.of("ignoreNull", true));
        assertDoesNotThrow(() -> registry.getGenerator("UNIQUE_VALIDATION").validateConfig(context));
        assertDoesNotThrow(() -> registry.getGenerator("COMPOSITE_UNIQUENESS_VALIDATION").validateConfig(context));
    }

    @Test
    void metadataValidatesOfflineButRequiresTrustedSnapshotsForExecution() {
        AuditRuleContext context = context();
        for (String strategy : List.of("MODEL_STRUCTURE_VALIDATION", "COLUMN_METADATA_COMPLETENESS")) {
            AuditSqlGenerator generator = registry.getGenerator(strategy);
            context.setConfig(strategy.startsWith("MODEL") ? Map.of("expectedColumns", List.of(Map.of("name", "id"))) : Map.of());
            assertDoesNotThrow(() -> generator.validateConfig(context));
            assertThrows(IllegalArgumentException.class, () -> generator.buildCountSql(context));
            assertThrows(IllegalArgumentException.class, () -> generator.buildGroupCountSql(context, "state"));
            assertThrows(IllegalArgumentException.class, () -> generator.buildCompositeKeyCountSql(context, List.of("id"), null));
        }
        context.setColumns(List.of()); context.setColumn(null); context.setConfig(Map.of());
        AuditSqlGenerator record = registry.getGenerator("RECORD_COMPLETENESS_VALIDATION");
        assertDoesNotThrow(() -> record.validateConfig(context));
        assertThrows(IllegalArgumentException.class, () -> record.buildCountSql(context));
    }

    @Test
    void quartilesFailClosedForUnprovenProductsAndRepeatScopesInEveryQuery() {
        AuditRuleContext context = context(); context.setColumn("amount"); context.setColumns(List.of("amount"));
        context.setConfig(Map.of("method", "IQR")); context.setWhereClause("active = true OR id = 90");
        context.addTextEqualsScope("population", "inside"); context.addTextInScope("phase", List.of("ready", "approved"));
        AuditSqlGenerator generator = registry.getGenerator("NUMERIC_OUTLIER_VALIDATION");
        for (String sql : List.of(generator.buildCountSql(context), generator.buildErrorDataSql(context, 10), generator.buildNormalDataSql(context, 10))) {
            assertEquals(2, (sql.length() - sql.replace("\"population\" = 'inside'", "").length()) / "\"population\" = 'inside'".length(), sql);
            assertTrue(sql.contains("qdata_fixture.amount >= q1"), sql);
        }
        for (ProductTypeEnum product : List.of(ProductTypeEnum.MYSQL, ProductTypeEnum.OPENGAUSS, ProductTypeEnum.GREENPLUM)) {
            context.setProductType(product);
            assertThrows(IllegalArgumentException.class, () -> generator.validateConfig(context));
        }
    }

    private static AuditRuleContext context() {
        AuditRuleContext context = new AuditRuleContext(DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL); context.setTableName("qdata_fixture");
        context.setColumn("state"); context.setColumns(List.of("state", "amount")); return context;
    }
}
