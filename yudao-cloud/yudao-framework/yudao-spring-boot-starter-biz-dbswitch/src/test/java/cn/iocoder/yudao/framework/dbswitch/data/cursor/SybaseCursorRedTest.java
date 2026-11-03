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
 * Red/green evidence for the Sybase cursor package: the same assertions fail against the frozen
 * snapshot classes (UNSUPPORTED declarations, no product upsert writer, no typed cursor admission)
 * and pass with the ASE 16.0 SP02 PL02 implementation. Class names are resolved reflectively so the
 * red phase compiles without the new product classes.
 */
class SybaseCursorRedTest {
    private static final String SYBASE_CURSOR = "SYBASE_ASE16_SP02_PL02_PINNED_GENERATION_SERIALIZABLE_ROWCOUNT_KEYSET";
    private static final String SYBASE_UPSERT = "SYBASE_ASE16_SP02_PL02_MERGE_EXACT_ORDERED_PRIMARY_KEY_COMMIT_RETURNED";

    @Test void sybaseDeclaresScopedCursorAndUpsertCapabilities() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, SYBASE_CURSOR),
                registry.product(ProductTypeEnum.SYBASE).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, SYBASE_UPSERT),
                registry.product(ProductTypeEnum.SYBASE).capabilities().get(DatabaseOperation.WRITE_UPSERT));
    }

    @Test void upsertFactoryProducesTheSybaseWriterWithoutOpeningConnections() throws Exception {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(DataSource.class);
        assertInstanceOf(Class.forName("cn.iocoder.yudao.framework.dbswitch.dbwriter.sybase.SybaseUpsertWriter"),
                registry.upsertWriter(source, ProductTypeEnum.SYBASE));
        verifyNoInteractions(source);
    }

    @Test void operatorDeclaresItsOwnTypedCursorAdmission() throws Exception {
        // Red: Sybase silently inherits the SQL Server snapshot cursor gate. Green: the product owns its admission.
        Class.forName("cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SybaseDatabaseOperator")
                .getDeclaredMethod("openCursor", CursorReadRequest.class);
    }

    @Test void configuredSourceGenerationsAdmitSybasePins() {
        var generation = new DbSwitchProperties.SourceGeneration();
        generation.setProduct(ProductTypeEnum.SYBASE);
        generation.setRestoreRotationRequired(true);
        generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(
                ProductTypeEnum.SYBASE, "jdbc:sybase:Tds:127.0.0.1:5000/tempdb", "sa"));
        generation.setSchema("s");
        generation.setInstanceId(java.util.UUID.randomUUID().toString());
        generation.setGeneration(java.util.UUID.randomUUID().toString());
        var pin = new ConfiguredSourceGenerations(Map.of("sybase", generation))
                .find(ProductTypeEnum.SYBASE, "jdbc:sybase:Tds:127.0.0.1:5000/tempdb", "sa");
        assertTrue(pin.isPresent());
    }
}
