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
 * Red/green evidence for the DB2 cursor package: the same assertions fail against the frozen
 * snapshot classes (UNSUPPORTED declarations, no product upsert writer, no typed cursor admission)
 * and pass with the DB2 LUW 12.1.5.0 implementation. Class names are resolved reflectively so the
 * red phase compiles without the new product classes.
 */
class Db2CursorRedTest {
    private static final String DB2_CURSOR = "DB2_LUW12150_PINNED_GENERATION_REPEATABLE_READ_FETCH_FIRST_KEYSET";
    private static final String DB2_UPSERT = "DB2_LUW12150_MERGE_EXACT_ORDERED_PRIMARY_KEY_COMMIT_RETURNED";

    @Test void db2DeclaresScopedCursorAndUpsertCapabilities() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, DB2_CURSOR),
                registry.product(ProductTypeEnum.DB2).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, DB2_UPSERT),
                registry.product(ProductTypeEnum.DB2).capabilities().get(DatabaseOperation.WRITE_UPSERT));
    }

    @Test void upsertFactoryProducesTheDb2WriterWithoutOpeningConnections() throws Exception {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(DataSource.class);
        assertInstanceOf(Class.forName("cn.iocoder.yudao.framework.dbswitch.dbwriter.db2.Db2UpsertWriter"),
                registry.upsertWriter(source, ProductTypeEnum.DB2));
        verifyNoInteractions(source);
    }

    @Test void operatorDeclaresItsOwnTypedCursorAdmission() throws Exception {
        // Red: DB2 inherits the generic openCursor refusal. Green: the product owns its admission.
        Class.forName("cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.DB2DatabaseOperator")
                .getDeclaredMethod("openCursor", CursorReadRequest.class);
    }

    @Test void configuredSourceGenerationsAdmitDb2Pins() {
        var generation = new DbSwitchProperties.SourceGeneration();
        generation.setProduct(ProductTypeEnum.DB2);
        generation.setRestoreRotationRequired(true);
        generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(
                ProductTypeEnum.DB2, "jdbc:db2://127.0.0.1:50000/Q17DB", "db2inst1"));
        generation.setSchema("s");
        generation.setInstanceId(java.util.UUID.randomUUID().toString());
        generation.setGeneration(java.util.UUID.randomUUID().toString());
        var pin = new ConfiguredSourceGenerations(Map.of("db2", generation))
                .find(ProductTypeEnum.DB2, "jdbc:db2://127.0.0.1:50000/Q17DB", "db2inst1");
        assertTrue(pin.isPresent());
    }
}
