package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogSelectBindingTest {
    private final CatalogRelationIdentity relation = new CatalogRelationIdentity(ProductTypeEnum.TIDB, "Catalog", "Orders", "42");

    @Test void dorisRequiresNativeGenerationAndExactCaseWithoutEnablingSyntaxOnlyBinding() {
        var doris = new CatalogRelationIdentity(ProductTypeEnum.DORIS, "Warehouse", "Orders", "a".repeat(64));
        var plan = RestrictedSelectGuard.bindToCatalogRelation(
                "select o.id from Orders o where o.id=? order by o.id limit 3", "Warehouse", doris);
        assertEquals("Warehouse", plan.getSchemaName());
        assertEquals("Orders", plan.getTableName());
        assertTrue(plan.getNormalizedSql().contains("`Warehouse`.`Orders` o"));
        assertTrue(plan.getNormalizedSql().contains("o.id = ?"));
        assertFalse(RestrictedSelectGuard.supportsExactBinding(ProductTypeEnum.DORIS));
        assertThrows(UnsupportedOperationException.class,
                () -> RestrictedSelectGuard.parseBound(ProductTypeEnum.DORIS, "select * from Orders", "Warehouse"));
        for (String sql : List.of("select * from orders", "select * from warehouse.Orders",
                "select * from internal.Warehouse.Orders", "select * from Orders join other on Orders.id=other.id",
                "select * from Orders; select * from other", "select * from Orders /* hint */")) {
            assertThrows(IllegalArgumentException.class,
                    () -> RestrictedSelectGuard.bindToCatalogRelation(sql, "Warehouse", doris), sql);
        }
        for (var invalid : List.of(
                new CatalogRelationIdentity(ProductTypeEnum.DORIS, "Warehouse", "Orders", "42"),
                new CatalogRelationIdentity(ProductTypeEnum.DORIS, "Warehouse", "Orders", "A".repeat(64)),
                new CatalogRelationIdentity(ProductTypeEnum.DORIS, "Warehouse.X", "Orders", "a".repeat(64)))) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.bindToCatalogRelation(
                    "select * from Orders", "Warehouse", invalid));
        }
    }

    @Test void canonicalizesOnlyTheFromRelationAndKeepsParametersAliasOrderingAndLimit() {
        var plan = RestrictedSelectGuard.bindToCatalogRelation(
                "SELECT o.id FROM catalog.orders o WHERE o.name=? OR o.id=? ORDER BY o.id DESC LIMIT 3 OFFSET 1",
                "ignored", relation);
        assertEquals("Catalog", plan.getSchemaName());
        assertEquals("Orders", plan.getTableName());
        assertTrue(plan.getNormalizedSql().contains("`Catalog`.`Orders` o"));
        assertEquals(2, plan.getNormalizedSql().chars().filter(ch -> ch == '?').count());
        assertTrue(plan.getNormalizedSql().contains("o.name = ?"));
        assertTrue(plan.getNormalizedSql().contains("ORDER BY o.id DESC"));
        var output = (com.alibaba.druid.sql.ast.statement.SQLSelectStatement)
                com.alibaba.druid.sql.SQLUtils.parseSingleStatement(plan.getNormalizedSql(), com.alibaba.druid.DbType.mysql);
        var limit = ((com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock) output.getSelect().getQuery()).getLimit();
        assertEquals("1", limit.getOffset().toString());
        assertEquals("3", limit.getRowCount().toString());
    }

    @Test void unqualifiedNamesUseTheExplicitSchemaAndWildcardsRemainForLaterAuthorization() {
        var plan = RestrictedSelectGuard.bindToCatalogRelation("select * from ORDERS", "CATALOG", relation);
        assertTrue(plan.isWildcard());
        assertEquals("SELECT *\nFROM `Catalog`.`Orders`", plan.getNormalizedSql());
        assertFalse(RestrictedSelectGuard.supportsExactBinding(ProductTypeEnum.TIDB));
        assertThrows(UnsupportedOperationException.class,
                () -> RestrictedSelectGuard.parseBound(ProductTypeEnum.TIDB, "select * from orders", "Catalog"));
    }

    @Test void absenceDifferentRelationInvalidIdentityAndUnsafeSqlDoNotFallBack() {
        for (String sql : List.of("select * from other.orders", "select * from Catalog.other",
                "select * from orders join other on orders.id=other.id", "select * from orders; select * from other")) {
            assertThrows(IllegalArgumentException.class,
                    () -> RestrictedSelectGuard.bindToCatalogRelation(sql, "Catalog", relation));
        }
        assertThrows(IllegalArgumentException.class,
                () -> RestrictedSelectGuard.bindToCatalogRelation("select * from orders", null, relation));
        assertThrows(UnsupportedOperationException.class,
                () -> RestrictedSelectGuard.bindToCatalogRelation("select * from orders", "Catalog", null));
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.bindToCatalogRelation(
                "select * from orders", "Catalog", new CatalogRelationIdentity(ProductTypeEnum.MYSQL,"Catalog","Orders","42")));
        for (CatalogRelationIdentity invalid : List.of(
                new CatalogRelationIdentity(ProductTypeEnum.TIDB,"Catalog","Orders","0"),
                new CatalogRelationIdentity(ProductTypeEnum.TIDB,"Catalog.Space","Orders","42"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> RestrictedSelectGuard.bindToCatalogRelation("select * from orders", "Catalog", invalid));
        }
    }

    @Test void nativeCatalogReferenceSyntaxRejectsAliasesCommentsAndTrailingClauses() {
        var syntax = RestrictedSelectGuard.parseCatalogRelationReference("`catalog`.`orders`", null);
        assertEquals("catalog", syntax.getSchemaName());
        assertEquals("orders", syntax.getTableName());
        for (String ref : List.of("catalog.orders o", "catalog.orders;", "catalog.orders --x", "catalog.orders WHERE id=1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> RestrictedSelectGuard.parseCatalogRelationReference(ref, "Catalog"));
        }
        assertThrows(UnsupportedOperationException.class,
                () -> RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.TIDB, "catalog.orders", null));
    }

    @Test void catalogRowFiltersRequirePhysicalKeysAndCannotElevateUserColumnAccess() {
        List<String> physical = List.of("id", "Name", "org_id", "secret");
        var allowed = java.util.Set.of("id", "Name");
        for (String sql : List.of("select secret as Name from orders", "select Name as secret from orders",
                "select Name from orders where SECRET='x'", "select Name from orders order by SeCrEt")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictCatalogProjectionWithRowFilters(
                    sql, "Catalog", relation, physical, allowed, List.of("secret='trusted'")));
        }
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictCatalogProjectionWithRowFilters(
                "select Name from orders", "Catalog", relation, physical, java.util.Set.of("NAME"), List.of()));
        for (String policy : List.of("org_id=?", "org_id IN (SELECT id FROM Other)", "org_id=7 LIMIT 1")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictCatalogProjectionWithRowFilters(
                    "select Name from orders", "Catalog", relation, physical, allowed, List.of(policy)));
        }
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.restrictProjectionWithRowFilters(
                ProductTypeEnum.TIDB, "select Name from orders", "Catalog", "Orders", physical, allowed, List.of()));
    }
}
