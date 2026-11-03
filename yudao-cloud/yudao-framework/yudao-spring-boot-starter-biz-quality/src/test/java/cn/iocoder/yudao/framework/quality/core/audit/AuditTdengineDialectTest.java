package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuditTdengineDialectTest {
    @Test void aliasesUseNativeQuotingWithoutRewritingTablePredicatesOrOtherProductsSql() {
        var context=new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());context.setProductType(ProductTypeEnum.TDENGINE);
        context.setTableName("source_db.sample");context.setColumn("amount");context.setConfig(Map.of("allowBlank",true));
        context.setWhereClause("id > 0");var generator=new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        for(String sql:List.of(generator.buildCountSql(context),generator.buildGroupCountSql(context,"id"),
                generator.buildGroupedKeyCountSql(context,"org_code","id"),generator.buildCompositeKeyCountSql(context,List.of("id","part"),"org_code"))) {
            assertFalse(sql.contains("\""));assertTrue(sql.contains("AS `totalCount`"));assertTrue(sql.contains("WHERE (id > 0)"));
        }
        context.setProductType(ProductTypeEnum.POSTGRESQL);
        assertEquals("SELECT COUNT(*) AS \"totalCount\", COUNT(CASE WHEN (amount IS NULL OR NOT (amount IS NOT NULL)) THEN 1 END) AS \"errorCount\" FROM source_db.sample WHERE (id > 0)",generator.buildCountSql(context));
    }
    @Test void productAlgorithmAndTypeMustMatch() {
        assertTrue(AuditKeySnapshot.supportsEncoding("TDENGINE",AuditKeySnapshot.ALGORITHM,Types.BIGINT));
        assertTrue(AuditKeySnapshot.supportsEncoding("TDENGINE",AuditKeySnapshot.TDENGINE_COMPOSITE_ALGORITHM,Types.STRUCT));
        assertFalse(AuditKeySnapshot.supportsEncoding("TDENGINE",AuditKeySnapshot.TDENGINE_COMPOSITE_ALGORITHM,Types.BIGINT));
        assertFalse(AuditKeySnapshot.supportsEncoding("TDENGINE",AuditKeySnapshot.ALGORITHM,Types.TIMESTAMP));
        assertFalse(AuditKeySnapshot.supportsEncoding("TDENGINE",AuditKeySnapshot.OPENGAUSS_COMPOSITE_ALGORITHM,Types.STRUCT));
        assertFalse(AuditKeySnapshot.supportsEncoding("OPENGAUSS",AuditKeySnapshot.TDENGINE_COMPOSITE_ALGORITHM,Types.STRUCT));
    }
}
