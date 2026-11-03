package cn.iocoder.yudao.framework.dbswitch.product.sybase;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSybaseImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SybaseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.sybase.SybaseWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.sybase.SybaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.sybase.SybaseDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class SybaseProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.SYBASE)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseSybaseImpl::new)
                .reader(SybaseDatabaseOperator::new)
                .writer(WriteMode.INSERT, SybaseWriterImpl::new)
                .synchronizer(SybaseDatabaseSyncImpl::new)
                .upsert(SybaseUpsertWriter::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "SYBASE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "SYBASE_ASE16_SP02_PL02_PINNED_GENERATION_SERIALIZABLE_ROWCOUNT_KEYSET")
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "SYBASE_ASE16_SP02_PL02_MERGE_EXACT_ORDERED_PRIMARY_KEY_COMMIT_RETURNED")
                .typeMapper(new SybaseFamilyLogicalTypeMapper(SybaseFamilyLogicalTypeMapper.Flavor.SYBASE))
                .cursorTargets(ProductTypeEnum.SYBASE)
                .build());
    }
}
