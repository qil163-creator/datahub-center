package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.DorisDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.doris.DorisDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由产品拥有的实现；声明不代表数据库认证。 */
public final class DorisProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.DORIS)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseDorisImpl::new)
                .reader(DorisDatabaseOperator::new)
                .writer(WriteMode.INSERT, DorisWriterImpl::new)
                .writer(WriteMode.COPY, source -> new DorisWriterImpl(source, true))
                .declare(DatabaseOperation.WRITE_COPY, "PINNED_NATIVE_STREAM_LOAD_VISIBLE_RECEIPT_BOUNDED_FULL_ROW")
                .synchronizer(DorisDatabaseSyncImpl::new)
                .upsert(DorisUpsertWriter::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "DORIS_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "DORIS_UNIQUE_KEY_MERGE_ON_WRITE_FULL_ROW_REPLACE_RECEIPT_ACKNOWLEDGED")
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "DORIS_PINNED_FIXED_UPPER_EXACT_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED")
                .typeMapper(new DorisLogicalTypeMapper())
                .cursorTargets(ProductTypeEnum.DORIS, ProductTypeEnum.POSTGRESQL)
                .build());
    }
}
