package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MigrationSourcePlanTest {
    private TableDescription table(String schema, String name) {
        var table = new TableDescription(); table.setSchemaName(schema); table.setTableName(name);
        table.setTableType("TABLE"); return table;
    }
    @Test void excludedChildrenCannotReenterThroughASelectedFamily() {
        var kept = table("selected", "keep");
        var result = MigrationService.validatePlannedSources(List.of(kept, table("selected", "excluded")),
                Set.of("selected"), Set.of("excluded"));
        assertEquals(List.of(kept), result);
    }
    @Test void foreignSchemaAndInjectedQueryFailTheEntirePlan() {
        var injected = table("selected", "leaf"); injected.setSql("select sensitive_value");
        for (var invalid : List.of(table("unselected", "leaf"), injected, table("selected", ""))) {
            assertThrows(IllegalStateException.class, () -> MigrationService.validatePlannedSources(
                    List.of(table("selected", "valid"), invalid), Set.of("selected"), Set.of()));
        }
    }
    @Test void duplicatePhysicalUnitsFailButSameNameInDistinctSelectedSchemasIsValid() {
        assertThrows(IllegalStateException.class, () -> MigrationService.validatePlannedSources(
                List.of(table("a", "leaf"), table("a", "leaf")), Set.of("a"), Set.of()));
        assertEquals(2, MigrationService.validatePlannedSources(List.of(table("a", "leaf"), table("b", "leaf")),
                Set.of("a", "b"), Set.of()).size());
    }
}
