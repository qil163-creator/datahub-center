package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.TypedRowFingerprint.KeyValue;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RestrictedRowLookupPlanTest {
    @Test void specialTextStaysBoundAndQuotedPhysicalNamesRemainExact() {
        for (String value : List.of("quoted'key", "back\\slash", "line\nbreak", "a' OR 1=1; DROP TABLE secret; --")) {
            var plan = RestrictedRowLookupPlan.create(ProductTypeEnum.POSTGRESQL, "Quoted.Schema", "Rows.Table", Map.of("Key.Value", KeyValue.of(value)));
            var checked = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL, plan.authorizationSql(), "other");
            assertEquals("Quoted.Schema", checked.getSchemaName()); assertEquals("Rows.Table", checked.getTableName());
            assertFalse(plan.authorizationSql().contains(value));
            var bound = plan.bindAuthorized(plan.authorizationSql());
            assertEquals(List.of(value), bound.parameters()); assertEquals(plan.authorizationSql(), bound.sql());
        }
    }

    @Test void authorizationCanAddLiteralPredicatesAndReorderCompositeBindsWithoutLosingTypes() {
        var uuid = UUID.fromString("8e12b7d8-0105-4000-9000-123456789abc");
        var plan = RestrictedRowLookupPlan.create(ProductTypeEnum.POSTGRESQL, "s", "rows",
                Map.of("id", KeyValue.of(9007199254740993L), "uid", KeyValue.of(uuid)));
        var bound = plan.bindAuthorized("SELECT * FROM s.rows r WHERE r.uid = ? AND r.id = ? AND r.visible = true");
        assertEquals(List.of(uuid, 9007199254740993L), bound.parameters());
    }

    @Test void missingDuplicateUnknownOrNonEqualityBindsAndChangedTargetsFailClosed() {
        var plan = RestrictedRowLookupPlan.create(ProductTypeEnum.POSTGRESQL, "s", "rows", Map.of("id", KeyValue.of(1L)));
        for (String sql : List.of("SELECT * FROM s.other WHERE id=?", "SELECT * FROM s.rows", "SELECT * FROM s.rows WHERE id=1",
                "SELECT * FROM s.rows WHERE id=? AND id=?", "SELECT * FROM s.rows WHERE other=?", "SELECT * FROM s.rows WHERE id>?",
                "SELECT * FROM s.rows WHERE id=? AND other=?", "DELETE FROM s.rows WHERE id=?"))
            assertThrows(IllegalArgumentException.class, () -> plan.bindAuthorized(sql), sql);
        assertThrows(IllegalArgumentException.class, () -> new KeyValue("INTEGER", "1 OR 1=1"));
        assertThrows(IllegalArgumentException.class, () -> new KeyValue("SQL", "now()"));
        assertThrows(UnsupportedOperationException.class, () -> RestrictedRowLookupPlan.create(ProductTypeEnum.MYSQL, "s", "rows", Map.of("id", KeyValue.of(1L))));
    }
}
