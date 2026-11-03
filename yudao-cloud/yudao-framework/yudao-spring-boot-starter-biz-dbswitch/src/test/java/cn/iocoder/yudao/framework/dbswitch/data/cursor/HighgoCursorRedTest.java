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
 * Red/green evidence for the HighGo cursor package: the same assertions fail against the frozen
 * snapshot classes (UNSUPPORTED declarations, no product writer, no typed admission) and pass with
 * the HighGo 6.0.4.2 implementation. Class names are resolved reflectively so the red phase
 * compiles without the new product classes.
 */
class HighgoCursorRedTest {
    private static final String HIGHGO_CURSOR = "HIGHGO_6_0_4_2_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET";
    private static final String HIGHGO_UPSERT = "HIGHGO_6_0_4_2_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED";

    @Test void highgoDeclaresScopedCursorAndUpsertCapabilities() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, HIGHGO_CURSOR),
                registry.product(ProductTypeEnum.HIGHGO).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, HIGHGO_UPSERT),
                registry.product(ProductTypeEnum.HIGHGO).capabilities().get(DatabaseOperation.WRITE_UPSERT));
    }

    @Test void upsertFactoryProducesTheHighgoWriterWithoutOpeningConnections() throws Exception {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(DataSource.class);
        assertInstanceOf(Class.forName("cn.iocoder.yudao.framework.dbswitch.dbwriter.highgo.HighgoUpsertWriter"),
                registry.upsertWriter(source, ProductTypeEnum.HIGHGO));
        verifyNoInteractions(source);
    }

    @Test void operatorDeclaresItsOwnTypedCursorAdmission() throws Exception {
        // Red: HighGo silently inherits the PostgreSQL operator's cursor gate. Green: the product owns its admission.
        Class.forName("cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.HighgoDatabaseOperator")
                .getDeclaredMethod("openCursor", CursorReadRequest.class);
    }

    @Test void configuredSourceGenerationsAdmitHighgoPins() {
        var generation = new DbSwitchProperties.SourceGeneration();
        generation.setProduct(ProductTypeEnum.HIGHGO);
        generation.setRestoreRotationRequired(true);
        generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(
                ProductTypeEnum.HIGHGO, "jdbc:highgo://127.0.0.1:5866/highgo", "highgo"));
        generation.setSchema("s");
        generation.setInstanceId(java.util.UUID.randomUUID().toString());
        generation.setGeneration(java.util.UUID.randomUUID().toString());
        var pin = new ConfiguredSourceGenerations(Map.of("highgo", generation))
                .find(ProductTypeEnum.HIGHGO, "jdbc:highgo://127.0.0.1:5866/highgo", "highgo");
        assertTrue(pin.isPresent());
    }
}
