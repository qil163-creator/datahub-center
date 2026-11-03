package cn.iocoder.yudao.framework.etl.core.connection;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EtlTableReferenceTest {
    @Test void legacyMetadataNamesPreserveTheirExactSpelling() {
        var result = EtlTableReference.resolve(connection(), "MixedCase", null);
        assertEquals("MixedCase", result.table());
        assertEquals("\"public\".\"MixedCase\"", result.quoted());
        var dotted = EtlTableReference.resolve(connection(), "Mixed.Table", null);
        assertEquals("Mixed.Table", dotted.table());
        assertEquals("\"public\".\"Mixed.Table\"", dotted.quoted());
    }
    @Test void explicitBindingsAndQuotedReferencesResolveOnePhysicalRelation() {
        var result = EtlTableReference.resolve(connection(), "\"other\".\"Mixed.Table\"", "other");
        assertEquals("other", result.schema()); assertEquals("Mixed.Table", result.table());
        assertEquals("\"other\".\"Mixed.Table\"", result.quoted());
        assertThrows(IllegalArgumentException.class, () -> EtlTableReference.resolve(connection(), "users; SELECT 1", "other"));
    }
    private EtlConnectionInfo connection() {
        var result = new EtlConnectionInfo(); result.setProductType(ProductTypeEnum.POSTGRESQL); result.setSchema("public"); return result;
    }
}
