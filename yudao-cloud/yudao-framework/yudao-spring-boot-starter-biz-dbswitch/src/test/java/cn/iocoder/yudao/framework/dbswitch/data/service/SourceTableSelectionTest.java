package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceTableSelectionTest {
    @Test void literalNamesAreIndependentAndPatternCharactersHaveNoSpecialMeaning() {
        var names = new ArrayList<>(List.of("a,b", "report?", "a*b", " leading", "trailing\u3000"));
        var source = source(names); var captured = source.copy(); names.clear();
        assertEquals(5, captured.getSourceTableNames().size());
        assertThrows(UnsupportedOperationException.class, () -> captured.getSourceTableNames().add("new"));
        for (String literal : captured.getSourceTableNames()) {
            var expected = table(literal);
            assertEquals(List.of(expected), MigrationService.selectSourceTables(ProductTypeEnum.POSTGRESQL,
                    null, List.of(literal), List.of(), List.of(expected, table("report1"), table("axb")),
                    ignored -> fail("Explicit selection must not apply default target discovery"), true));
        }
    }

    @Test void typedSelectionRejectsAmbiguityBeforeRuntimeSnapshotAndRetainsLegacyPatternApi() {
        var source = source(List.of("literal")); source.setSourceIncludes("legacy");
        assertThrows(IllegalArgumentException.class, source::copy);
        source.setSourceIncludes(""); source.setSourceExcludes("ignored legacy?");
        assertEquals(List.of("literal"), source.copy().getSourceTableNames());
        source.setSourceType(2); assertThrows(IllegalArgumentException.class, source::copy);
        var legacy = new SourceDataSourceProperties(); legacy.setSourceIncludes("report.*");
        assertNull(legacy.copy().getSourceTableNames());
        assertEquals(1, MigrationService.selectSourceTables(ProductTypeEnum.POSTGRESQL, null,
                List.of("report.*"), List.of(), List.of(table("report1")), List::copyOf).size());
    }

    @Test void invalidAndDuplicateNamesCannotBecomeASelection() {
        for (String name : Arrays.asList(null, "", "bad\0name", "x".repeat(1025)))
            assertThrows(IllegalArgumentException.class, () -> SourceTableSelection.requireExactNames(Arrays.asList(name)));
        assertThrows(IllegalArgumentException.class, () -> SourceTableSelection.requireExactNames(List.of("same", "same")));
        assertThrows(IllegalArgumentException.class, () -> SourceTableSelection.requireExactNames(java.util.Collections.nCopies(10_001, "name")));
    }

    private static SourceDataSourceProperties source(List<String> names) {
        var source = new SourceDataSourceProperties(); source.setIncludeOrExclude(1); source.setSourceTableNames(names); return source;
    }
    private static TableDescription table(String name) {
        var table = new TableDescription(); table.setSchemaName("source"); table.setTableName(name); table.setTableType("TABLE"); return table;
    }
}
