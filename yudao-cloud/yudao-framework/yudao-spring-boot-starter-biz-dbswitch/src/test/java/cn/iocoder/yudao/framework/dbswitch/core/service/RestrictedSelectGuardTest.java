package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestrictedSelectGuardTest {

    @Test
    void standaloneTableReferencePreservesIdentityAndRejectsSqlTail() {
        var plan = RestrictedSelectGuard.parseTableReference(ProductTypeEnum.POSTGRESQL,
                "\"Gov.Schema\".\"Citizen\"\". One\"", "public");
        assertEquals("Gov.Schema", plan.getSchemaName());
        assertEquals("Citizen\". One", plan.getTableName());
        assertEquals("SELECT * FROM \"Gov.Schema\".\"Citizen\"\". One\"", plan.getNormalizedSql());
        var bare = RestrictedSelectGuard.parseTableReference(ProductTypeEnum.POSTGRESQL, "Citizen", "public");
        assertEquals("citizen", bare.getTableName());
        for (String invalid : java.util.List.of("citizen c", "citizen where 1=1", "citizen; select * from secret",
                "citizen join secret on true", "citizen;", "catalog.public.citizen", "(select * from citizen)")) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parseTableReference(
                    ProductTypeEnum.POSTGRESQL, invalid, "public"), invalid);
        }
    }

    @Test
    void physicalNamesAreNotTruncatedOrInterpretedAsSql() {
        assertEquals("\"Gov.Schema\".\"a;b\"", RestrictedSelectGuard.qualifiedTableName(
                ProductTypeEnum.POSTGRESQL, "Gov.Schema", "a;b"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parseTableReference(
                ProductTypeEnum.POSTGRESQL, "x".repeat(64), "public"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.qualifiedTableName(
                ProductTypeEnum.POSTGRESQL, "public", "字".repeat(22)));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.qualifiedTableName(
                ProductTypeEnum.POSTGRESQL, "public", "a\0b"));
    }

    @Test
    void postgresNamesFollowIdentifierRulesWithoutLosingQuotedCharacters() {
        var folded = RestrictedSelectGuard.parse(ProductTypeEnum.POSTGRESQL,
                "select ID from PUBLIC.Citizen");
        assertEquals("public", folded.getSchemaName());
        assertEquals("citizen", folded.getTableName());
        assertEquals(java.util.Set.of("id"), folded.getSourceColumns());
        var quoted = RestrictedSelectGuard.parse(ProductTypeEnum.POSTGRESQL,
                "select \"C\"\".Id\" from \"S\"\".One\".\"T\"\".Two\"");
        assertEquals("S\".One", quoted.getSchemaName());
        assertEquals("T\".Two", quoted.getTableName());
        assertEquals(java.util.Set.of("C\".Id"), quoted.getSourceColumns());
    }

    @Test
    void threePartNamesCannotSilentlyDiscardTheCatalog() {
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parse(
                ProductTypeEnum.POSTGRESQL, "select * from other.public.citizen"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.POSTGRESQL, "select * from other.public.citizen", "public", "citizen",
                java.util.List.of("id"), java.util.Set.of("id")));
    }

    @Test
    void boundPlanPinsTheConfiguredSchemaAndPreservesExplicitQualification() {
        var plan = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                "select c.ID from Citizen c where c.ID > 1 order by c.ID", "Gov.Schema");
        assertEquals("Gov.Schema", plan.getSchemaName());
        assertEquals("citizen", plan.getTableName());
        assertTrue(plan.getNormalizedSql().contains("\"Gov.Schema\".\"citizen\""));
        var explicit = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                "select * from OTHER.Citizen", "ignored");
        assertEquals("other", explicit.getSchemaName());
        assertEquals("citizen", explicit.getTableName());
    }

    @Test
    void missingSchemaAndUnverifiedCaseRulesCannotBeGuessed() {
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parseBound(
                ProductTypeEnum.POSTGRESQL, "select * from citizen", null));
        for (var product : java.util.List.of(ProductTypeEnum.TIDB, ProductTypeEnum.DORIS,
                ProductTypeEnum.STARROCKS, ProductTypeEnum.GBASE8A)) {
            assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.parseBound(
                    product, "select * from citizen", "public"), product.name());
        }
    }

    @Test
    void postgresBackslashRemainsLiteralAndBrokenQuotesAreRejected() {
        var plan = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                "select \"a\\b\" from \"a\\b\".\"c\\d\"", null);
        assertEquals("a\\b", plan.getSchemaName());
        assertEquals("c\\d", plan.getTableName());
        assertEquals(java.util.Set.of("a\\b"), plan.getSourceColumns());
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parse(
                ProductTypeEnum.POSTGRESQL, "select * from \"broken"));
    }

    @Test
    void mysqlAndMariaDbBindTheConfiguredDatabaseWithoutFoldingCase() {
        for (var product : java.util.List.of(ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB)) {
            var bare = RestrictedSelectGuard.parseBound(product, "select ID from Citizen", "App");
            assertEquals("App", bare.getSchemaName(), product.name());
            assertEquals("Citizen", bare.getTableName(), product.name());
            assertEquals(java.util.Set.of("ID"), bare.getSourceColumns(), product.name());
            assertTrue(bare.getNormalizedSql().contains("`App`.`Citizen`"), bare.getNormalizedSql());
            var explicit = RestrictedSelectGuard.parseBound(product,
                    "select id from `Gov.Db`.`A``.B`", "ignored");
            assertEquals("Gov.Db", explicit.getSchemaName(), product.name());
            assertEquals("A`.B", explicit.getTableName(), product.name());
            assertTrue(explicit.getNormalizedSql().contains("`Gov.Db`.`A``.B`"), explicit.getNormalizedSql());
            assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parseBound(
                    product, "select id from citizen", " "), product.name());
            var reference = RestrictedSelectGuard.parseTableReference(product, "`Gov.Db`.`A``.B`", "ignored");
            assertEquals("SELECT * FROM `Gov.Db`.`A``.B`", reference.getNormalizedSql(), product.name());
        }
    }

    @Test
    void acceptsSimpleWildcardSelect() {
        RestrictedSelectPlan plan = RestrictedSelectGuard.parse(ProductTypeEnum.POSTGRESQL,
                "SELECT * FROM stats.monthly_cases");
        assertEquals("monthly_cases", plan.getTableName());
        assertTrue(plan.isWildcard());
        assertTrue(plan.getSourceColumns().isEmpty());
    }

    @Test
    void acceptsAliasedColumns() {
        RestrictedSelectPlan plan = RestrictedSelectGuard.parse(ProductTypeEnum.MYSQL,
                "SELECT t.district_code AS code, t.case_count FROM monthly_cases t");
        assertEquals("monthly_cases", plan.getTableName());
        assertFalse(plan.isWildcard());
        assertTrue(plan.getSourceColumns().contains("district_code"));
        assertTrue(plan.getSourceColumns().contains("case_count"));
    }

    @Test
    void rejectsJoinCteSubqueryAndWrites() {
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parse(
                ProductTypeEnum.POSTGRESQL, "SELECT a.id FROM t1 a JOIN t2 b ON a.id = b.id"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parse(
                ProductTypeEnum.POSTGRESQL, "WITH x AS (SELECT 1 AS id) SELECT * FROM x"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parse(
                ProductTypeEnum.POSTGRESQL, "SELECT * FROM (SELECT * FROM secrets) s"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parse(
                ProductTypeEnum.POSTGRESQL, "SELECT * FROM t WHERE id IN (SELECT id FROM secrets)"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parse(
                ProductTypeEnum.POSTGRESQL, "UPDATE t SET x = 1"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parse(
                ProductTypeEnum.POSTGRESQL, "SELECT * FROM t; SELECT * FROM secrets"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parse(
                ProductTypeEnum.POSTGRESQL, "SELECT count(*) FROM t"));
    }

    @Test
    void failsClosedForUnverifiedProduct() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> RestrictedSelectGuard.parse(ProductTypeEnum.MONGODB, "SELECT * FROM dual"));
        assertTrue(error.getMessage().contains("尚未验证"));
    }
}
