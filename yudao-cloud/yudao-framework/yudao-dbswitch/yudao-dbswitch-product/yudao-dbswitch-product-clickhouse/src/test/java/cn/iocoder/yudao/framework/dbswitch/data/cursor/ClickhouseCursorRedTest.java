package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.ClickhouseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Red/green evidence for the ClickHouse cursor package: the same assertions fail against the frozen snapshot classes
 *  (UNSUPPORTED declarations, missing upsert factory) and pass with the new implementation. */
class ClickhouseCursorRedTest {
    private static final String CLICKHOUSE_CURSOR =
            "CLICKHOUSE_PINNED_FIXED_UPPER_SORTING_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED";
    private static final String CLICKHOUSE_UPSERT =
            "CLICKHOUSE_EXCLUSIVE_TARGET_OWNERSHIP_MUTATION_READBACK_UPSERT";

    @Test void clickhouseDeclaresScopedCursorAndUpsertCapabilities() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, CLICKHOUSE_CURSOR),
                registry.product(ProductTypeEnum.CLICKHOUSE).capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, CLICKHOUSE_UPSERT),
                registry.product(ProductTypeEnum.CLICKHOUSE).capabilities().get(DatabaseOperation.WRITE_UPSERT));
    }

    @Test void upsertFactoryProducesTheProductWriterWithoutOpeningConnections() throws Exception {
        var registry = DbSwitchProductRegistry.discover();
        var source = mock(DataSource.class);
        assertInstanceOf(Class.forName("cn.iocoder.yudao.framework.dbswitch.dbwriter.clickhouse.ClickhouseUpsertWriter"),
                registry.upsertWriter(source, ProductTypeEnum.CLICKHOUSE));
        verifyNoInteractions(source);
    }

    @Test void operatorDeclaresItsOwnTypedCursorAdmission() throws Exception {
        ClickhouseDatabaseOperator.class.getDeclaredMethod("openCursor", CursorReadRequest.class);
    }
}
