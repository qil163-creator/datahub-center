package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.DorisDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.StarrocksDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Red/green evidence for the OLAP cursor package: the same assertions fail against the frozen snapshot classes
 *  (UNSUPPORTED declarations, legacy UnsupportedOperationException) and pass with the new implementation. */
class OlapCursorRedTest {
    private static final String DORIS_CURSOR = "DORIS_PINNED_FIXED_UPPER_EXACT_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED";
    private static final String DORIS_UPSERT = "DORIS_UNIQUE_KEY_MERGE_ON_WRITE_FULL_ROW_REPLACE_RECEIPT_ACKNOWLEDGED";
    private static final String STARROCKS_CURSOR = "STARROCKS_PINNED_FIXED_UPPER_EXACT_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED";
    private static final String STARROCKS_UPSERT = "STARROCKS_PRIMARY_KEY_FULL_ROW_REPLACE_RECEIPT_ACKNOWLEDGED";

    @Test void dorisAndStarrocksDeclareScopedCursorAndUpsertCapabilities() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, DORIS_CURSOR),
                registry.product(ProductTypeEnum.DORIS).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, DORIS_UPSERT),
                registry.product(ProductTypeEnum.DORIS).capabilities().get(DatabaseOperation.WRITE_UPSERT));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, STARROCKS_CURSOR),
                registry.product(ProductTypeEnum.STARROCKS).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, STARROCKS_UPSERT),
                registry.product(ProductTypeEnum.STARROCKS).capabilities().get(DatabaseOperation.WRITE_UPSERT));
    }

    @Test void upsertFactoriesProduceTheProductWritersWithoutOpeningConnections() throws Exception {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(DataSource.class);
        assertInstanceOf(Class.forName("cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisUpsertWriter"),
                registry.upsertWriter(source, ProductTypeEnum.DORIS));
        assertInstanceOf(Class.forName("cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksUpsertWriter"),
                registry.upsertWriter(source, ProductTypeEnum.STARROCKS));
        verifyNoInteractions(source);
    }

    @Test void operatorsDeclareTheirOwnTypedCursorAdmission() throws Exception {
        // Red: Doris/StarRocks silently inherit the MySQL operator's cursor gate. Green: each product declares its own.
        DorisDatabaseOperator.class.getDeclaredMethod("openCursor", CursorReadRequest.class);
        StarrocksDatabaseOperator.class.getDeclaredMethod("openCursor", CursorReadRequest.class);
    }
}
