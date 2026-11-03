package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuditOracleDialectTest {
    @Test void nonNullUsesNativeLengthAndFetchFirstForDetailRows() {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());context.setProductType(ProductTypeEnum.ORACLE);
        context.setTableName("S.SAMPLE");context.setColumn("AMOUNT");context.setConfig(Map.of());
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        String counts=generator.buildCountSql(context), detail=generator.buildErrorDataSql(context,50);
        assertTrue(counts.contains("LENGTH(AMOUNT) > 0"), counts);
        assertTrue(counts.contains("AS \"totalCount\""));assertFalse(counts.contains("<> ''"));
        assertTrue(detail.endsWith(" FETCH FIRST 50 ROWS ONLY"));assertFalse(detail.contains(" LIMIT "));
        context.setConfig(Map.of("allowBlank",true));assertFalse(generator.buildCountSql(context).contains("LENGTH"));
    }
    @Test void productAlgorithmAndTypeMustMatch() throws Exception {
        var encoded = AuditKeySnapshot.sortedEncodedSnapshot("SELECT fixture", new java.util.ArrayList<>(java.util.List.of(
                new AuditKeySnapshot.EncodedKey(new byte[]{1,2},1,0,java.sql.Types.STRUCT))), AuditKeySnapshot.ORACLE_COMPOSITE_ALGORITHM);
        assertEquals(AuditKeySnapshot.ORACLE_COMPOSITE_ALGORITHM,encoded.encoding());
        assertEquals(AuditKeySnapshot.ORACLE_COMPOSITE_ALGORITHM,encoded.algorithm());
        assertTrue(AuditKeySnapshot.isCompositeEncoding(AuditKeySnapshot.ORACLE_COMPOSITE_ALGORITHM));
        assertTrue(AuditKeySnapshot.supportsEncoding("ORACLE",AuditKeySnapshot.ORACLE_COMPOSITE_ALGORITHM,java.sql.Types.STRUCT));
        assertTrue(AuditKeySnapshot.supportsEncoding("ORACLE",AuditKeySnapshot.ALGORITHM,java.sql.Types.SMALLINT));
        assertTrue(AuditKeySnapshot.supportsEncoding("ORACLE",AuditKeySnapshot.ALGORITHM,java.sql.Types.INTEGER));
        assertTrue(AuditKeySnapshot.supportsEncoding("ORACLE",AuditKeySnapshot.ALGORITHM,java.sql.Types.BIGINT));
        assertFalse(AuditKeySnapshot.supportsEncoding("ORACLE",AuditKeySnapshot.ORACLE_COMPOSITE_ALGORITHM,java.sql.Types.BIGINT));
        assertFalse(AuditKeySnapshot.supportsEncoding("ORACLE",AuditKeySnapshot.ALGORITHM,java.sql.Types.NUMERIC));
        assertFalse(AuditKeySnapshot.supportsEncoding("ORACLE",AuditKeySnapshot.ALGORITHM,java.sql.Types.DECIMAL));
        assertFalse(AuditKeySnapshot.supportsEncoding("ORACLE",AuditKeySnapshot.UXDB_COMPOSITE_ALGORITHM,java.sql.Types.STRUCT));
        for(var product:ProductTypeEnum.values()) if(product!=ProductTypeEnum.ORACLE) {
            assertFalse(AuditKeySnapshot.supportsEncoding(product.name(),AuditKeySnapshot.ORACLE_COMPOSITE_ALGORITHM,java.sql.Types.STRUCT));
        }
    }
}
