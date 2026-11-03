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
 * Red/green evidence for the Hive cursor package: the same assertions fail against the frozen
 * snapshot classes (UNSUPPORTED declarations, no product upsert writer, no typed cursor admission)
 * and pass with the Hive 4.1.0 implementation. Class names are resolved reflectively so the
 * red phase compiles without the new product classes.
 */
class HiveCursorRedTest {
    private static final String HIVE_CURSOR = "HIVE41_PINNED_FIXED_UPPER_DIRECTORY_IDENTITY_KEYSET_WITHOUT_SNAPSHOT";
    private static final String HIVE_UPSERT = "HIVE41_FULL_ACID_ORC_EXACT_KEY_INSERT_UPDATE_READBACK_ACKNOWLEDGED";

    @Test void hiveDeclaresScopedCursorAndUpsertCapabilities() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, HIVE_CURSOR),
                registry.product(ProductTypeEnum.HIVE).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, HIVE_UPSERT),
                registry.product(ProductTypeEnum.HIVE).capabilities().get(DatabaseOperation.WRITE_UPSERT));
    }

    @Test void upsertFactoryProducesTheHiveWriterWithoutOpeningConnections() throws Exception {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(DataSource.class);
        assertInstanceOf(Class.forName("cn.iocoder.yudao.framework.dbswitch.dbwriter.hive.HiveUpsertWriter"),
                registry.upsertWriter(source, ProductTypeEnum.HIVE));
        verifyNoInteractions(source);
    }

    @Test void operatorDeclaresItsOwnTypedCursorAdmission() throws Exception {
        // Red: Hive inherits the generic openCursor refusal. Green: the product owns its admission.
        Class.forName("cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.HiveDatabaseOperator")
                .getDeclaredMethod("openCursor", CursorReadRequest.class);
    }

    @Test void configuredSourceGenerationsAdmitHivePins() {
        var generation = new DbSwitchProperties.SourceGeneration();
        generation.setProduct(ProductTypeEnum.HIVE);
        generation.setRestoreRotationRequired(true);
        generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(
                ProductTypeEnum.HIVE, "jdbc:hive2://127.0.0.1:57555/default", "hive"));
        generation.setSchema("s");
        generation.setInstanceId(java.util.UUID.randomUUID().toString());
        generation.setGeneration(java.util.UUID.randomUUID().toString());
        var pin = new ConfiguredSourceGenerations(Map.of("hive", generation))
                .find(ProductTypeEnum.HIVE, "jdbc:hive2://127.0.0.1:57555/default", "hive");
        assertTrue(pin.isPresent());
    }
}
