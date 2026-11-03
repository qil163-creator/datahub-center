package cn.iocoder.yudao.framework.dbswitch.product.hive;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.HiveDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.hive.HiveUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.hive.HiveWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.hive.HiveDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class HiveProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.HIVE)
                .typeMapper(new HiveLogicalTypeMapper())
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseHiveImpl::new)
                .reader(HiveDatabaseOperator::new)
                .writer(WriteMode.INSERT, HiveWriterImpl::new)
                .synchronizer(HiveDatabaseSyncImpl::new)
                .upsert(HiveUpsertWriter::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "HIVE_TEXT_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "HIVE41_PINNED_FIXED_UPPER_DIRECTORY_IDENTITY_KEYSET_WITHOUT_SNAPSHOT")
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "HIVE41_FULL_ACID_ORC_EXACT_KEY_INSERT_UPDATE_READBACK_ACKNOWLEDGED")
                .cursorTargets(ProductTypeEnum.HIVE)
                .build());
    }
}
