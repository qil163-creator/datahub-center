package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExactSelectBindingTest {
    private static final Set<ProductTypeEnum> SUPPORTED = Set.of(
            ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB, ProductTypeEnum.OPENGAUSS);

    @Test void exactBindingCapabilityIsIndependentFromProjectionDialects() {
        assertFalse(RestrictedSelectGuard.supportsExactBinding(null));
        for (var product : ProductTypeEnum.values()) {
            assertEquals(SUPPORTED.contains(product), RestrictedSelectGuard.supportsExactBinding(product), product.name());
            if (!SUPPORTED.contains(product)) {
                assertThrows(UnsupportedOperationException.class,
                        () -> RestrictedSelectGuard.parseBound(product, "SELECT id FROM orders", "tenant"));
                assertThrows(UnsupportedOperationException.class,
                        () -> RestrictedSelectGuard.parseTableReference(product, "tenant.orders", null));
                assertThrows(UnsupportedOperationException.class,
                        () -> RestrictedSelectGuard.restrictProjectionWithRowFilters(product,
                                "SELECT id FROM orders", "tenant", "orders", List.of("id"), Set.of("id"), List.of()));
            }
        }
        // TiDB does have projection syntax support. Case aliases still require a native catalog binding.
        assertEquals("Orders", RestrictedSelectGuard.parse(ProductTypeEnum.TIDB,
                "SELECT id FROM Orders").getTableName());
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "MYSQL", "MARIADB", "OPENGAUSS"})
    void bindsDefaultSchemaAndKeepsQuotedPhysicalCase(ProductTypeEnum product) {
        String table = product.isLikePostgres() ? "\"Orders\"" : "`Orders`";
        var plan = RestrictedSelectGuard.parseBound(product, "SELECT id FROM " + table, "Tenant");
        assertEquals("Tenant", plan.getSchemaName());
        assertEquals("Orders", plan.getTableName());
        assertTrue(plan.getNormalizedSql().contains(RestrictedSelectGuard.qualifiedTableName(product, "Tenant", "Orders")));
        var reference = RestrictedSelectGuard.parseTableReference(product,
                RestrictedSelectGuard.qualifiedTableName(product, "Tenant", "Orders"), "wrong");
        assertEquals("Tenant", reference.getSchemaName());
        assertEquals("Orders", reference.getTableName());
        assertThrows(IllegalArgumentException.class,
                () -> RestrictedSelectGuard.parseBound(product, "SELECT id FROM orders", null));
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "MYSQL", "MARIADB", "OPENGAUSS"})
    void trustedRowPredicatesDoNotExpandUserColumnPermissions(ProductTypeEnum product) {
        var physical = List.of("id", "name", "org_id", "secret");
        var merged = RestrictedSelectGuard.restrictProjectionWithRowFilters(product,
                "SELECT * FROM orders WHERE id=1 OR id=2 ORDER BY id LIMIT 3", "tenant", "orders",
                physical, Set.of("id", "name"), List.of("org_id=9 OR org_id=10", "secret IS NOT NULL"));
        assertEquals(Set.of("id", "name"), merged.selectedColumns());
        assertEquals(Set.copyOf(physical), merged.referencedColumns());
        assertTrue(merged.sql().matches("(?s).*WHERE\\s*\\(.*"), merged.sql());
        for (String query : List.of("SELECT secret FROM orders", "SELECT id FROM orders WHERE org_id=11",
                "SELECT id FROM orders ORDER BY secret", "SELECT id FROM wrong.orders")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjectionWithRowFilters(
                    product, query, "tenant", "orders", physical, Set.of("id", "name"), List.of("org_id=9")));
        }
    }

    @Test void openGaussBindingRetainsCaseRulesAndRejectsUnverifiedRelationNames() {
        assertEquals("orders", RestrictedSelectGuard.parseTableReference(
                ProductTypeEnum.OPENGAUSS, "tenant.ORDERS", null).getTableName());
        assertEquals("Orders", RestrictedSelectGuard.parseTableReference(
                ProductTypeEnum.OPENGAUSS, "tenant.\"Orders\"", null).getTableName());
        for (String name : List.of("\"order.name\"", "\"two words\"", "\"na\"\"me\"")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parseBound(
                    ProductTypeEnum.OPENGAUSS, "SELECT id FROM " + name, "tenant"));
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parseTableReference(
                    ProductTypeEnum.OPENGAUSS, "tenant." + name, null));
        }
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjectionWithRowFilters(
                ProductTypeEnum.OPENGAUSS, "SELECT id FROM \"two words\"", "tenant", "two words",
                List.of("id"), Set.of("id"), List.of()));
    }
}
