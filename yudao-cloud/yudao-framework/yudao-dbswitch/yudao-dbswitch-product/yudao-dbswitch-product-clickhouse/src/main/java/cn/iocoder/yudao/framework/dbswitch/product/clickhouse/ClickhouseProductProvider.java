package cn.iocoder.yudao.framework.dbswitch.product.clickhouse;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseClickhouseImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.ClickhouseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.clickhouse.ClickhouseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.clickhouse.ClickhouseWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.clickhouse.ClickhouseDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由产品拥有的实现；声明不代表数据库认证。 */
public final class ClickhouseProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.CLICKHOUSE)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseClickhouseImpl::new)
                .reader(ClickhouseDatabaseOperator::new)
                .writer(WriteMode.INSERT, ClickhouseWriterImpl::new)
                .upsert(ClickhouseUpsertWriter::new)
                .synchronizer(ClickhouseDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "CLICKHOUSE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "CLICKHOUSE_PINNED_FIXED_UPPER_SORTING_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED")
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "CLICKHOUSE_EXCLUSIVE_TARGET_OWNERSHIP_MUTATION_READBACK_UPSERT")
                .typeMapper(new ClickHouseLogicalTypeMapper())
                .cursorTargets(ProductTypeEnum.CLICKHOUSE)
                .build());
    }
}
