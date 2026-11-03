package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditTypedScopeTest {

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MYSQL", "MARIADB", "TIDB", "OCEANBASE", "POSTGRESQL"})
    void modeDependentBackslashLiteralsFailClosedInBothScopeOperators(ProductTypeEnum product) {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(product); context.setTableName("fixture"); context.setColumn("id"); context.setConfig(Map.of());
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        context.addTextEqualsScope("batch_key", "unsafe\\' OR TRUE --");
        assertThatThrownBy(() -> generator.buildCountSql(context)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.buildErrorDataSql(context, 30)).isInstanceOf(IllegalArgumentException.class);
        context.getScopePredicates().clear();
        context.addTextInScope("batch_key", java.util.List.of("safe", "unsafe\\' OR TRUE --"));
        assertThatThrownBy(() -> generator.buildCountSql(context)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.buildErrorDataSql(context, 30)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyOptionalScopeDoesNotProduceAnEmptyWhereClause() {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL); context.setTableName("public.fixture");
        context.setColumn("id"); context.setConfig(Map.of()); context.setWhereClause("  ");
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        assertThat(generator.buildCountSql(context)).doesNotContain("WHERE");
        assertThat(generator.buildErrorDataSql(context, 30)).doesNotContain("AND ()");
    }

    @Test
    void batchAndAdmissionPredicatesApplyToBothCountsAndIssueDetails() {
        AuditRuleContext context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL);
        context.setTableName("public.etl_target");
        context.setColumn("customer_id");
        context.setConfig(Map.of());
        context.setWhereClause("customer_id IS NULL OR customer_id IS NOT NULL");
        context.addTextEqualsScope("_etl_batch_key", "ab".repeat(32));
        context.addTextInScope("_quality_status", java.util.List.of("PASS", "WARN"));

        AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        String countSql = generator.buildCountSql(context);
        String detailSql = generator.buildErrorDataSql(context, 30);
        String predicates = "(customer_id IS NULL OR customer_id IS NOT NULL) AND \"_etl_batch_key\" = '" + "ab".repeat(32)
                + "' AND \"_quality_status\" IN ('PASS', 'WARN')";

        assertThat(countSql).contains("WHERE (" + predicates + ")");
        assertThat(detailSql).contains("AND (" + predicates + ")");
    }

    @Test
    void textValuesAreQuotedAndIdentifierQuotingFailsClosed() {
        AuditRuleContext context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL);
        context.setTableName("public.etl_target");
        context.setColumn("customer_id");
        context.setConfig(Map.of());
        context.addTextEqualsScope("batch_key", "safe' OR TRUE --");
        AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        assertThat(generator.buildCountSql(context)).contains("\"batch_key\" = 'safe'' OR TRUE --'");

        context.setProductType(ProductTypeEnum.SQLITE3);
        assertThat(generator.buildCountSql(context)).contains("\"batch_key\" = 'safe'' OR TRUE --'");
        context.addTextEqualsScope("bad\u0000key", "safe");
        assertThatThrownBy(() -> generator.buildCountSql(context)).isInstanceOf(IllegalArgumentException.class);
    }
}
