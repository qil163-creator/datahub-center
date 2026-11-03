package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchFileTargetAuthorizationTest {
    @Test void insertPreservesNormalizedSqlAndExactBindingOrder() {
        var adapter = new BatchFileTargetAuthorization(); var route = route();
        String sql = GenerateSqlUtils.getParameterizedInsertSql(ProductTypeEnum.MYSQL, "warehouse", "target", List.of("编号", "内容"), 2);
        var result = adapter.authorize(route, 7L, "target", sql, "DML", 4);
        assertEquals(List.of(0, 1, 2, 3), result.parameterOrder());
        assertEquals(List.of(1, "first", 2, "second"), result.bind(List.of(1, "first", 2, "second")));
        assertEquals(ManagedSchemaScriptPlan.parseInsertOnly(ProductTypeEnum.MYSQL, "warehouse", List.of(sql))
                .steps().getFirst().sql(), result.sql());
        assertThrows(IllegalArgumentException.class, () -> result.bind(List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> ManagedSchemaScriptPlan.parse(ProductTypeEnum.MYSQL, "warehouse", List.of(sql)),
                "含 DDL 的脚本入口不得替代 INSERT 专用计划");
    }
    @Test void rejectsWrongSchemaTargetOperationAndParameterCount() {
        var adapter = new BatchFileTargetAuthorization();
        String sql = "INSERT INTO `warehouse`.`target` (`id`, `value`) VALUES (?, ?)";
        assertThrows(RuntimeException.class, () -> adapter.authorize(route(), 7L, "another", sql, "DML", 2));
        assertThrows(RuntimeException.class, () -> adapter.authorize(route(), 7L, "target", sql, "DML", 1));
        assertThrows(RuntimeException.class, () -> adapter.authorize(route(), 7L, "target", sql, "DDL", 2));
        assertThrows(RuntimeException.class, () -> adapter.authorize(route(), 7L, "target", sql.replace("warehouse", "other"), "DML", 2));
        assertThrows(RuntimeException.class, () -> adapter.authorize(route(), 7L, "target", sql + "; DELETE FROM target", "DML", 2));
    }
    @Test void createTableUsesManagedPlanAndCannotEscapeTargetSchema() {
        var adapter = new BatchFileTargetAuthorization();
        var result = adapter.authorize(route(), 7L, "target", "CREATE TABLE target(id BIGINT)", "DDL", 0);
        assertTrue(result.sql().contains("warehouse"));
        assertEquals(List.of(), result.parameterOrder());
        assertThrows(RuntimeException.class, () -> adapter.authorize(route(), 7L, "target", "CREATE TABLE other.target(id BIGINT)", "DDL", 0));
    }
    @Test void invalidIdentityAndNonInsertAreRejected() {
        var adapter = new BatchFileTargetAuthorization();
        assertThrows(RuntimeException.class, () -> adapter.authorize(route(), null, "target", "INSERT INTO target(id) VALUES (?)", "DML", 1));
        var stale = route(); stale.setConfigurationRevision(null);
        assertThrows(RuntimeException.class, () -> adapter.authorize(stale, 7L, "target", "INSERT INTO target(id) VALUES (?)", "DML", 1));
        assertThrows(RuntimeException.class, () -> adapter.authorize(route(), 7L, "target", "DELETE FROM target", "DML", 0));
    }
    static AccessDatabaseResolver.ConnectionInfo route() {
        var route = new AccessDatabaseResolver.ConnectionInfo(); route.setDatabaseId(1L); route.setProductType(ProductTypeEnum.MYSQL);
        route.setSchema("warehouse"); route.setConfigurationRevision("revision-1"); return route;
    }
}
