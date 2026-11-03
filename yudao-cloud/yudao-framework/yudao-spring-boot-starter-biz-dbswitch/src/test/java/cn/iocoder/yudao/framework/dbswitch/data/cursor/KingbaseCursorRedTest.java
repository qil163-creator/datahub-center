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
 * Red/green evidence for the Kingbase cursor package: the same assertions fail against the frozen
 * snapshot classes (UNSUPPORTED declarations, no product writer, no typed admission) and pass with
 * the KingbaseES V008R006C009B0014 implementation. Class names are resolved reflectively so the red
 * phase compiles without the new product classes.
 */
class KingbaseCursorRedTest {
    private static final String KINGBASE_CURSOR = "KINGBASE_ORACLE_CI_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET";
    private static final String KINGBASE_UPSERT = "KINGBASE_ORACLE_CI_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED";

    @Test void kingbaseDeclaresScopedCursorAndUpsertCapabilities() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, KINGBASE_CURSOR),
                registry.product(ProductTypeEnum.KINGBASE).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, KINGBASE_UPSERT),
                registry.product(ProductTypeEnum.KINGBASE).capabilities().get(DatabaseOperation.WRITE_UPSERT));
    }

    @Test void upsertFactoryProducesTheKingbaseWriterWithoutOpeningConnections() throws Exception {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(DataSource.class);
        assertInstanceOf(Class.forName("cn.iocoder.yudao.framework.dbswitch.dbwriter.kingbase.KingbaseUpsertWriter"),
                registry.upsertWriter(source, ProductTypeEnum.KINGBASE));
        verifyNoInteractions(source);
    }

    @Test void operatorDeclaresItsOwnTypedCursorAdmission() throws Exception {
        // Red: Kingbase silently inherits the PostgreSQL operator's cursor gate. Green: the product owns its admission.
        Class.forName("cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.KingbaseDatabaseOperator")
                .getDeclaredMethod("openCursor", CursorReadRequest.class);
    }

    @Test void configuredSourceGenerationsAdmitKingbasePins() {
        var generation = new DbSwitchProperties.SourceGeneration();
        generation.setProduct(ProductTypeEnum.KINGBASE);
        generation.setRestoreRotationRequired(true);
        generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(
                ProductTypeEnum.KINGBASE, "jdbc:kingbase8://127.0.0.1:54321/test", "system"));
        generation.setSchema("s");
        generation.setInstanceId(java.util.UUID.randomUUID().toString());
        generation.setGeneration(java.util.UUID.randomUUID().toString());
        var pin = new ConfiguredSourceGenerations(Map.of("kingbase", generation))
                .find(ProductTypeEnum.KINGBASE, "jdbc:kingbase8://127.0.0.1:54321/test", "system");
        assertTrue(pin.isPresent());
    }
}
