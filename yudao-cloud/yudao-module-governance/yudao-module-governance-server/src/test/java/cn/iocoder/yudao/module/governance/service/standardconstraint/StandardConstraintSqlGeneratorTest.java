package cn.iocoder.yudao.module.governance.service.standardconstraint;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StandardConstraintSqlGeneratorTest {

    private final AuditSqlRegistry registry = new AuditSqlRegistry();

    @Test
    void numericRangeShouldExecuteIndependentOpenAndClosedBoundaries() {
        String sql = sql("NUMERIC_RANGE_VALIDATION", config(
                "minValue", "0", "maxValue", "100", "includeMin", false, "includeMax", true));

        assertThat(sql).contains("amount > 0").contains("amount <= 100");
    }

    @Test
    void numericRangeShouldRemainBackwardCompatibleWithIncludeBoundary() {
        String sql = sql("NUMERIC_RANGE_VALIDATION", config(
                "minValue", "0", "maxValue", "100", "includeBoundary", false));

        assertThat(sql).contains("amount > 0").contains("amount < 100");
    }

    @Test
    void precisionScaleShouldConstrainBothTotalDigitsAndFractionDigits() {
        String sql = sql("DECIMAL_PRECISION_VALIDATION", config(
                "precision", 10, "scale", 2, "skipInteger", true, "ignoreNullValue", true));

        assertThat(sql).contains("<= 10").contains("<= 2");
        assertThat(sql).contains("= 0 OR");
        assertThat(sql).contains("LIKE '0.%'").contains("LIKE '-0.%'");
    }

    @Test
    void dateFormatStrategyShouldBeRegisteredAndUseSafeRegex() {
        String sql = sql("DATE_FORMAT_VALIDATION", config(
                "pattern", "yyyy-MM-dd", "regex", "^\\d{4}-\\d{2}-\\d{2}$", "ignoreNullValue", true));

        assertThat(sql).contains(" ~ '").contains("\\d{4}");
    }

    @Test
    void mysqlRegexAndEnumLiteralsShouldBeModeIndependentHexValues() {
        String regexSql = sql(ProductTypeEnum.MYSQL, "REGEX_VALIDATION",
                config("regex", "^\\d+'; DROP TABLE x; --$", "ignoreNullValue", true));
        String enumSql = sql(ProductTypeEnum.DORIS, "ENUM_VALIDATION",
                config("validValues", java.util.List.of("\\'; DROP TABLE x; --"),
                        "ignoreCase", false, "ignoreNullValue", true));

        assertThat(regexSql).contains("REGEXP CONVERT(X'")
                .doesNotContain("DROP TABLE").doesNotContain("\\\\d");
        assertThat(enumSql).contains("IN (CONVERT(X'")
                .doesNotContain("DROP TABLE").doesNotContain("\\\\'");
    }

    @Test
    void numericNonNullSqlMustNotCompareZeroWithBlankString() {
        for (ProductTypeEnum product : java.util.List.of(ProductTypeEnum.MYSQL,
                ProductTypeEnum.POSTGRESQL, ProductTypeEnum.DORIS)) {
            String sql = sql(product, "NOT_NULL_VALIDATION", config("allowBlank", true));
            assertThat(sql).contains("amount IS NULL").doesNotContain("<> ''");
        }
    }

    @Test
    void mysqlCaseSensitiveEnumMustOverrideCiCollation() {
        String sql = sql(ProductTypeEnum.MYSQL, "ENUM_VALIDATION",
                config("validValues", java.util.List.of("abc"), "ignoreCase", false));
        assertThat(sql).contains("BINARY amount IN (CONVERT(X'616263' USING utf8mb4))");
    }

    @Test
    void mysqlRegexMustOverrideCiCollation() {
        String sql = sql(ProductTypeEnum.MYSQL, "REGEX_VALIDATION",
                config("regex", "^[A-Z]+$", "ignoreNullValue", true));
        assertThat(sql).contains("BINARY amount REGEXP CONVERT(X'5e5b412d5a5d2b24' USING utf8mb4)");
    }

    private String sql(String key, Map<String, Object> config) {
        return sql(ProductTypeEnum.POSTGRESQL, key, config);
    }

    private String sql(ProductTypeEnum productType, String key, Map<String, Object> config) {
        AuditSqlGenerator generator = registry.getGenerator(key);
        assertThat(generator).as("registered strategy %s", key).isNotNull();
        AuditRuleContext context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(productType);
        context.setTableName("orders");
        context.setColumn("amount");
        context.setConfig(config);
        return generator.buildCountSql(context);
    }

    private static Map<String, Object> config(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], values[i + 1]);
        }
        return result;
    }
}
