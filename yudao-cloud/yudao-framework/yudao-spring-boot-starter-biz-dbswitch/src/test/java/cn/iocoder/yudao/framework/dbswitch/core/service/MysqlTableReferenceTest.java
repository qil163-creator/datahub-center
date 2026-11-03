package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class MysqlTableReferenceTest {
    @Test
    void preservesCaseEscapesQuotesAndDoesNotParseDotsInsideIdentifiers() {
        var plan = RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.MYSQL, "`Gov.Db`.`A``.B`", "default_db");
        assertEquals("Gov.Db", plan.getSchemaName());
        assertEquals("A`.B", plan.getTableName());
        assertEquals("SELECT * FROM `Gov.Db`.`A``.B`", plan.getNormalizedSql());
        assertEquals("Citizen", RestrictedSelectGuard.parseRelationReference(
                ProductTypeEnum.MYSQL, "Citizen", "default_db").getTableName());
        assertEquals("`schema`.`" + "字".repeat(64) + "`", RestrictedSelectGuard.qualifiedTableName(
                ProductTypeEnum.MYSQL, "schema", "字".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.quotePhysicalIdentifier(
                ProductTypeEnum.MYSQL, "x".repeat(65)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"t alias", "t where 1=1", "t;", "t;select 1", "a.b.c", "(select * from t)",
            "t join secret on true", "\"t\"", "'t'", "[t]", "t/*comment*/", "t/*!50100 PARTITION(p0) */",
            "t#comment", "t -- comment", "`a\0b`", "`unterminated"})
    void rejectsAmbiguousReferencesAndSqlTails(String reference) {
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parseRelationReference(
                ProductTypeEnum.MYSQL, reference, "schema"), reference);
    }

    @Test
    void bindsMySqlReferencesWithoutEnablingOtherMysqlCompatibleProducts() {
        var bound = RestrictedSelectGuard.parseBound(ProductTypeEnum.MYSQL, "SELECT id FROM t", "schema");
        assertEquals("schema", bound.getSchemaName());
        assertEquals("t", bound.getTableName());
        assertTrue(bound.getNormalizedSql().contains("`schema`.`t`"), bound.getNormalizedSql());
        assertEquals("SELECT * FROM `schema`.`t`", RestrictedSelectGuard.parseTableReference(
                ProductTypeEnum.MYSQL, "t", "schema").getNormalizedSql());
        // 关系名语法或投影方言已经启用，仍不等于精确表定位。TIDB 守住这条边界。
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.parseBound(
                ProductTypeEnum.TIDB, "SELECT id FROM t", "schema"));
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.parseTableReference(
                ProductTypeEnum.TIDB, "t", "schema"));
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.parseRelationReference(
                ProductTypeEnum.TIDB, "t", "schema"));
        assertDoesNotThrow(() -> RestrictedSelectGuard.parseRelationReference(
                ProductTypeEnum.MARIADB, "t", "schema"));
    }
}
