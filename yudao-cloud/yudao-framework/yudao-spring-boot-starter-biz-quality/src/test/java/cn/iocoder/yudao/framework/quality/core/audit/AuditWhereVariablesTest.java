package cn.iocoder.yudao.framework.quality.core.audit;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditWhereVariablesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 15);

    @Test
    void rendersDateVariablesAsQuotedLiteralsWhetherOrNotUserQuotedThem() {
        assertThat(AuditWhereVariables.render("biz_date = ${bizdate}", DAY)).isEqualTo("biz_date = '2026-11-15'");
        assertThat(AuditWhereVariables.render("biz_date = '${bizdate}' AND pt = ${bizdate_nodash} OR m = ${bizmonth}",
                DAY)).isEqualTo("biz_date = '2026-11-15' AND pt = '20261115' OR m = '2026-11'");
        assertThat(AuditWhereVariables.hasVariables("a = ${bizdate}")).isTrue();
        assertThat(AuditWhereVariables.hasVariables("a = 1")).isFalse();
    }

    @Test
    void leavesPlainConditionsUntouched() {
        assertThat(AuditWhereVariables.render(null, DAY)).isNull();
        assertThat(AuditWhereVariables.render("status = 'PAID'", DAY)).isEqualTo("status = 'PAID'");
    }

    @Test
    void rejectsUnknownMalformedOrEmbeddedVariables() {
        assertThatThrownBy(() -> AuditWhereVariables.render("d = ${today}", DAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不支持的变量 ${today}");
        assertThatThrownBy(() -> AuditWhereVariables.render("d = '${bizdate}", DAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("单引号");
        assertThatThrownBy(() -> AuditWhereVariables.render("d = 'x${bizdate}'", DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditWhereVariables.render("d = ${biz date}", DAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("格式");
    }

}
