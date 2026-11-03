package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

class AccessTypeMappingDiagnosticsTest {
    @Test void boundedDiagnosticsPreserveOverallRejectionAndKeepBlockersBeforeTheExactColumnSummary() {
        var columns = new ArrayList<ColumnTypeMapping>();
        for (int i = 0; i < 1200; i++) columns.add(new ColumnTypeMapping("column_" + i, SQLITE3, "T".repeat(1024),
                "L".repeat(1024), POSTGRESQL, "N".repeat(1024), TypeFidelity.EXACT, List.of(), "placeholder"));
        columns.add(new ColumnTypeMapping("blocked", SQLITE3, "UNKNOWN", null, POSTGRESQL, null, TypeFidelity.UNSUPPORTED,
                List.of(TypeMappingIssue.blocker("INCOMPLETE_METADATA", "native type was not observed")), null));
        var source = new TableTypeMappingPlan(POSTGRESQL, columns, Set.of());
        var diagnostic = AccessTypeMappingDiagnostics.from(source, "main", "source", "migration", "target");
        assertTrue(diagnostic.truncated()); assertEquals(1201, diagnostic.totalColumns());
        assertFalse(diagnostic.accepted()); assertEquals("UNSUPPORTED", diagnostic.fidelity());
        assertEquals("blocked", diagnostic.columns().getFirst().column());
        String json = AccessTypeMappingDiagnostics.write(diagnostic); assertTrue(json.length() <= 1_000_000);
        var response = AccessTypeMappingDiagnostics.readResponse(json);
        assertTrue(response.isTruncated()); assertFalse(response.isAccepted()); assertEquals(1201, response.getTotalColumns());
        assertEquals(1201, source.columns().size()); assertFalse(source.accepted());
    }

    @Test void rejectingPlanDoesNotExposeRenderedDdlOrConvertUnsupportedColumnsIntoAcknowledgedLosses() {
        var mapping = new ColumnTypeMapping("amount", SQLITE3, "DECIMAL(12,2)", null, POSTGRESQL, null, TypeFidelity.UNSUPPORTED,
                List.of(TypeMappingIssue.blocker("DECIMAL_STORED_AS_REAL_OR_TEXT", "exact decimal storage is not established")), null);
        var plan = new TableTypeMappingPlan(POSTGRESQL, List.of(mapping), Set.of());
        var diagnostic = AccessTypeMappingDiagnostics.from(plan, "main", "source", "migration", "target");
        assertFalse(diagnostic.truncated()); assertEquals(1, diagnostic.totalColumns());
        var column = diagnostic.columns().getFirst(); assertFalse(column.accepted()); assertTrue(column.lossCodes().isEmpty());
        String json = AccessTypeMappingDiagnostics.write(diagnostic);
        assertFalse(json.contains("definition")); assertFalse(json.contains("jdbc:"));
        var longContext = AccessTypeMappingDiagnostics.from(plan, "main", "source", "schema".repeat(100), "target".repeat(100));
        assertTrue(longContext.truncated()); assertEquals(128, longContext.targetSchema().length());
        assertEquals(256, longContext.targetTable().length()); assertFalse(longContext.accepted());
        assertEquals(1, longContext.totalColumns());
    }
}
