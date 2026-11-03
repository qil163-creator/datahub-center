package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.product.gbase8a.Gbase8aProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 注册仅声明原厂表锁和暂存 MERGE 能力；缺少可验证连接仍必须在准备阶段拒绝。
 * 此单测验证声明与打包清单一致，真实事务语义由原厂测试证明。
 */
class Gbase8aUpsertRejectedTest {
    private static final String MANIFEST = "META-INF/dbswitch/products/gbase8a.json";

    @Test void cursorColumnEvidenceKeepsPhysicalOrdinalAndDefensivelyCopiesTheCatalog() {
        var order = new java.util.ArrayList<>(java.util.List.of("ID", "WM", "NOTE", "PART"));
        for (int pass = 0; pass < 4; pass++) {
            var columns = new java.util.LinkedHashMap<String, cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aCursorMetadata.Column>();
            for (String name : order) columns.put(name, new cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aCursorMetadata.Column(
                    name, "int", "int", false, 10, 0, null, null, "", null, ""));
            var shape = new cn.iocoder.yudao.framework.dbswitch.core.util.Gbase8aCursorMetadata.Shape(
                    "s", "t", "EXPRESS", "BASE TABLE", "", "observed", "ddl", "verified", "123", columns);
            assertEquals(order, new java.util.ArrayList<>(shape.columns().keySet()));
            String evidence = shape.columnEvidence();
            columns.clear();
            assertEquals(evidence, shape.columnEvidence());
            assertThrows(UnsupportedOperationException.class, () -> shape.columns().clear());
            java.util.Collections.rotate(order, 1);
        }
    }

    @Test
    void providerAndManifestExposeOnlyTheLockedNativeMergeProfile() throws Exception {
        var products = new Gbase8aProductProvider().products();
        assertEquals(1, products.size());
        var product = products.iterator().next();
        assertEquals(ProductTypeEnum.GBASE8A, product.product());
        var upsert = product.capabilities().get(DatabaseOperation.WRITE_UPSERT);
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                "GBASE8A_86243_EXPRESS_LOCKED_SINGLE_MERGE_EXACT_STAGING"), upsert);

        try (var input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
            assertNotNull(input, MANIFEST);
            var declared = new ObjectMapper().readTree(input).path("operations").path("WRITE_UPSERT");
            assertEquals("DECLARED", declared.path("status").asText());
            assertEquals(upsert.status().name(), declared.path("status").asText());
            assertEquals(upsert.reason(), declared.path("reason").asText());
        }

        // 注册成功不能代替实际产品、版本、表结构和唯一数据的准入。
        var writer = product.upsertWriter(mock(javax.sql.DataSource.class));
        assertEquals(cn.iocoder.yudao.framework.dbswitch.dbwriter.gbase8a.Gbase8aUpsertWriter.class, writer.getClass());
        assertThrows(IllegalArgumentException.class,
                () -> writer.prepareUpsert("s", "t", java.util.List.of("id"), java.util.List.of("id")));
    }
}
