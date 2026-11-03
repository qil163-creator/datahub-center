package cn.iocoder.yudao.framework.dbswitch.product.starrocks;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseStarrocksImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.StarrocksDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.starrocks.StarrocksDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由产品拥有的实现；声明不代表数据库认证。 */
public final class StarrocksProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.STARROCKS)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseStarrocksImpl::new)
                .reader(StarrocksDatabaseOperator::new)
                .writer(WriteMode.INSERT, StarrocksWriterImpl::new)
                .writer(WriteMode.COPY, source -> new StarrocksWriterImpl(source, true))
                .declare(DatabaseOperation.WRITE_COPY, "PINNED_NATIVE_STREAM_LOAD_VISIBLE_RECEIPT_BOUNDED_FULL_ROW")
                .synchronizer(StarrocksDatabaseSyncImpl::new)
                .upsert(StarrocksUpsertWriter::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "STARROCKS_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.WRITE_UPSERT, "STARROCKS_PRIMARY_KEY_FULL_ROW_REPLACE_RECEIPT_ACKNOWLEDGED")
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "STARROCKS_PINNED_FIXED_UPPER_EXACT_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED")
                .typeMapper(new StarrocksLogicalTypeMapper())
                .cursorTargets(ProductTypeEnum.STARROCKS)
                .build());
    }
}
