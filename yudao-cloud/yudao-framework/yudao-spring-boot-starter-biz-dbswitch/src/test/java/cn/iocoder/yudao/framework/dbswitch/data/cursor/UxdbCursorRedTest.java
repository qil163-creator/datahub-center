package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Red/green evidence for the UXDB cursor package: the same assertions fail against the frozen
 * snapshot classes (UNSUPPORTED declarations, no product writer, no typed admission) and pass with
 * the UXsinoDB 2.1.0.3 implementation. Class names are resolved reflectively so the red phase
 * compiles without the new product classes.
 */
class UxdbCursorRedTest {
    private static final String UXDB_CURSOR = "UXSINODB_2_1_0_3_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET";
    private static final String UXDB_UPSERT = "UXSINODB_2_1_0_3_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED";

    @Test void uxdbDeclaresScopedCursorAndUpsertCapabilities() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, UXDB_CURSOR),
                registry.product(ProductTypeEnum.UXDB).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, UXDB_UPSERT),
                registry.product(ProductTypeEnum.UXDB).capabilities().get(DatabaseOperation.WRITE_UPSERT));
    }

    @Test void upsertFactoryProducesTheUxdbWriterWithoutOpeningConnections() throws Exception {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(DataSource.class);
        assertInstanceOf(Class.forName("cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb.UxdbUpsertWriter"),
                registry.upsertWriter(source, ProductTypeEnum.UXDB));
        verifyNoInteractions(source);
    }

    @Test void operatorDeclaresItsOwnTypedCursorAdmission() throws Exception {
        // Red: UXDB silently inherits the PostgreSQL operator's cursor gate. Green: the product owns its admission.
        Class.forName("cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.UxdbDatabaseOperator")
                .getDeclaredMethod("openCursor", CursorReadRequest.class);
    }

    @Test void configuredSourceGenerationsAdmitUxdbPins() {
        var generation = new DbSwitchProperties.SourceGeneration();
        generation.setProduct(ProductTypeEnum.UXDB);
        generation.setRestoreRotationRequired(true);
        generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(
                ProductTypeEnum.UXDB, "jdbc:uxdb://127.0.0.1:54324/uxdb", "uxdb"));
        generation.setSchema("s");
        generation.setInstanceId(java.util.UUID.randomUUID().toString());
        generation.setGeneration(java.util.UUID.randomUUID().toString());
        var pin = new ConfiguredSourceGenerations(Map.of("uxdb", generation))
                .find(ProductTypeEnum.UXDB, "jdbc:uxdb://127.0.0.1:54324/uxdb", "uxdb");
        assertTrue(pin.isPresent());
    }
}
