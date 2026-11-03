package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestrictedDmlGuardTest {

    @Test
    void acceptsSingleTableUpdate() {
        RestrictedDmlPlan plan = RestrictedDmlGuard.parse(ProductTypeEnum.POSTGRESQL,
                "UPDATE orders SET status = 'fixed' WHERE id = 1");
        assertEquals(RestrictedDmlPlan.Kind.UPDATE, plan.getKind());
        RestrictedDmlGuard.assertAuthorizedTable(plan, "orders");
    }

    @Test
    void allowsSameTableSubquery() {
        RestrictedDmlPlan plan = RestrictedDmlGuard.parse(ProductTypeEnum.POSTGRESQL,
                "DELETE FROM orders WHERE id NOT IN (SELECT MIN(id) FROM orders GROUP BY mobile)");
        RestrictedDmlGuard.assertAuthorizedTable(plan, "ORDERS");
    }

    @Test
    void rejectsMultipleStatements() {
        assertThrows(IllegalArgumentException.class, () -> RestrictedDmlGuard.parse(
                ProductTypeEnum.POSTGRESQL, "UPDATE orders SET status = 1; DELETE FROM secrets"));
    }

    @Test
    void rejectsCrossTableAndSelect() {
        assertThrows(IllegalArgumentException.class, () -> RestrictedDmlGuard.parse(
                ProductTypeEnum.MYSQL, "UPDATE orders SET x = (SELECT y FROM secrets)"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedDmlGuard.parse(
                ProductTypeEnum.MYSQL, "INSERT INTO orders SELECT * FROM secrets"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedDmlGuard.parse(
                ProductTypeEnum.POSTGRESQL, "SELECT * FROM orders"));
    }

    @Test
    void rejectsUnauthorizedTable() {
        RestrictedDmlPlan plan = RestrictedDmlGuard.parse(ProductTypeEnum.POSTGRESQL,
                "UPDATE secrets SET x = 1");
        assertThrows(IllegalArgumentException.class,
                () -> RestrictedDmlGuard.assertAuthorizedTable(plan, "orders"));
    }

    @Test
    void failsClosedForUnverifiedProduct() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> RestrictedDmlGuard.parse(ProductTypeEnum.ORACLE, "UPDATE dual SET dummy = dummy"));
        assertTrue(error.getMessage().contains("尚未验证"));
    }
}
