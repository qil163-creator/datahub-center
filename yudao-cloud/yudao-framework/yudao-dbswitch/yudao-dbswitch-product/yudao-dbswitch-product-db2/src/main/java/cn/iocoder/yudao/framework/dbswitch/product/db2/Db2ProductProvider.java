package cn.iocoder.yudao.framework.dbswitch.product.db2;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDB2Impl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.DB2DatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.db2.DB2WriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.db2.Db2UpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.db2.DB2DatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由具体产品负责的实现；能力声明不等于数据库认证。 */
public final class Db2ProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.DB2)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseDB2Impl::new)
                .reader(DB2DatabaseOperator::new)
                .writer(WriteMode.INSERT, DB2WriterImpl::new)
                .synchronizer(DB2DatabaseSyncImpl::new)
                .upsert(Db2UpsertWriter::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "DB2_LUW12150_PINNED_GENERATION_REPEATABLE_READ_FETCH_FIRST_KEYSET")
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "DB2_LUW12150_MERGE_EXACT_ORDERED_PRIMARY_KEY_COMMIT_RETURNED")
                .declare(DatabaseOperation.DDL_ALTER, "DB2_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .typeMapper(new Db2FamilyLogicalTypeMapper(Db2FamilyLogicalTypeMapper.Flavor.DB2))
                .cursorTargets(ProductTypeEnum.DB2)
                .build());
    }
}
