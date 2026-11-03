package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuditSqlServerDialectTest {
    @Test void nonNullUsesLengthIncludingSpacesAndTopForDetailRows() {
        var context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());context.setProductType(ProductTypeEnum.SQLSERVER);
        context.setTableName("[s].[sample]");context.setColumn("amount");context.setConfig(Map.of());
        var generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
        String counts=generator.buildCountSql(context), detail=generator.buildErrorDataSql(context,50);
        assertTrue(counts.contains("DATALENGTH(CONVERT(nvarchar(max), [amount])) > 0"), counts);
        assertTrue(counts.contains("AS [totalCount]"));assertFalse(counts.contains("<> ''"));
        assertTrue(detail.startsWith("SELECT TOP (50) *"));assertFalse(detail.contains(" LIMIT "));
        context.setConfig(Map.of("allowBlank",true));assertFalse(generator.buildCountSql(context).contains("CONVERT"));
    }
    @Test void scanEncodingIsNotAnAutomaticGovernanceAcceptanceGate() throws Exception {
        var encoded = AuditKeySnapshot.sortedEncodedSnapshot("SELECT fixture", new java.util.ArrayList<>(java.util.List.of(
                new AuditKeySnapshot.EncodedKey(new byte[]{1,2},1,0,java.sql.Types.STRUCT))), AuditKeySnapshot.SQLSERVER_COMPOSITE_ALGORITHM);
        assertEquals(AuditKeySnapshot.SQLSERVER_COMPOSITE_ALGORITHM,encoded.encoding());
        assertEquals(AuditKeySnapshot.SQLSERVER_COMPOSITE_ALGORITHM,encoded.algorithm());
        assertTrue(AuditKeySnapshot.isCompositeEncoding(AuditKeySnapshot.SQLSERVER_COMPOSITE_ALGORITHM));
        assertFalse(AuditKeySnapshot.supportsEncoding("SQLSERVER",AuditKeySnapshot.SQLSERVER_COMPOSITE_ALGORITHM,java.sql.Types.STRUCT));
        assertFalse(AuditKeySnapshot.supportsEncoding("SQLSERVER2000",AuditKeySnapshot.ALGORITHM,java.sql.Types.BIGINT));
        for(var product:ProductTypeEnum.values()) assertFalse(AuditKeySnapshot.supportsEncoding(product.name(),AuditKeySnapshot.SQLSERVER_COMPOSITE_ALGORITHM,java.sql.Types.STRUCT));
    }
}
