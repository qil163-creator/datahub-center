package cn.iocoder.yudao.module.dataforge.service.fill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Excel 导入按唯一标识查重：跳过或覆盖
 */
class FillExcelImportPlanTest {

    private final List<FillExcelServiceImpl.ExistingRow> existing = List.of(
            new FillExcelServiceImpl.ExistingRow("r1", Map.of("code", "A", "amount", "1")),
            new FillExcelServiceImpl.ExistingRow("r2", Map.of("code", "B", "amount", "2")));

    private final List<Map<String, Object>> rows = List.of(
            Map.of("code", "A", "amount", "10"),
            Map.of("code", "C", "amount", "30"),
            Map.of("code", "C", "amount", "31"),
            Map.of("amount", "99"));

    @Test
    void skipKeepsExistingRowsAndFirstInFileDuplicate() {
        var plan = FillExcelServiceImpl.planImport(rows, existing, List.of("code"), false);

        assertThat(plan.updates()).isEmpty();
        assertThat(plan.adds()).containsExactly(Map.of("amount", "99"), Map.of("code", "C", "amount", "30"));
        assertThat(plan.skipped()).isEqualTo(2);
    }

    @Test
    void overwriteUpdatesExistingRowsAndLastInFileDuplicateWins() {
        var plan = FillExcelServiceImpl.planImport(rows, existing, List.of("code"), true);

        assertThat(plan.updates()).containsExactly(Map.entry("r1", Map.of("code", "A", "amount", "10")));
        assertThat(plan.adds()).containsExactly(Map.of("amount", "99"), Map.of("code", "C", "amount", "31"));
        assertThat(plan.skipped()).isEqualTo(1);
    }

    @Test
    void withoutKeysEverythingIsAppended() {
        var plan = FillExcelServiceImpl.planImport(rows, existing, List.of(), true);

        assertThat(plan.adds()).hasSize(4);
        assertThat(plan.updates()).isEmpty();
        assertThat(plan.skipped()).isZero();
    }

}
