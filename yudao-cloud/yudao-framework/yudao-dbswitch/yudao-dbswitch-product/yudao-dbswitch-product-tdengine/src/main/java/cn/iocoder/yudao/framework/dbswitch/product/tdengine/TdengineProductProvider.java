package cn.iocoder.yudao.framework.dbswitch.product.tdengine;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTdengineImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.TdengineDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.tdengine.TdengineUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.tdengine.TdengineWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.tdengine.TdengineDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由产品拥有的实现；声明不代表数据库认证。 */
public final class TdengineProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.TDENGINE)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseTdengineImpl::new)
                .reader(TdengineDatabaseOperator::new)
                .writer(WriteMode.INSERT, TdengineWriterImpl::new)
                .synchronizer(TdengineDatabaseSyncImpl::new)
                .upsert(TdengineUpsertWriter::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "TDENGINE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "TDENGINE_TIMESTAMP_KEY_OVERWRITE_REQUEST_ACKNOWLEDGED_VERIFIED_TAG_ROUTING")
                .declare(DatabaseOperation.SYNC_CURSOR, "TDENGINE_PINNED_FIXED_UPPER_TIMESTAMP_KEYSET_WITHOUT_SNAPSHOT")
                .typeMapper(new TdengineLogicalTypeMapper())
                .cursorTargets(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.TDENGINE)
                .build());
    }
}
