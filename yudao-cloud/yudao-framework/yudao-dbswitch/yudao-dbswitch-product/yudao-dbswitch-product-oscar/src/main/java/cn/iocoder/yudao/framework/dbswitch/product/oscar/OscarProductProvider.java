package cn.iocoder.yudao.framework.dbswitch.product.oscar;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOscarImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.OscarDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oscar.OscarWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oscar.OscarUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.oscar.OscarDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class OscarProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.OSCAR)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseOscarImpl::new)
                .reader(OscarDatabaseOperator::new)
                .writer(WriteMode.INSERT, OscarWriterImpl::new)
                .synchronizer(OscarDatabaseSyncImpl::new)
                .upsert(OscarUpsertWriter::new)
                .typeMapper(new OscarLogicalTypeMapper())
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "OSCAR_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "OSCAR_V8_0_251217_PINNED_GENERATION_LOCKED_NUMERIC_KEYSET")
                .declare(DatabaseOperation.WRITE_UPSERT, "OSCAR_V8_0_251217_EXCLUSIVE_LOCK_MERGE_ORDERED_PRIMARY_KEY")
                .cursorTargets(ProductTypeEnum.OSCAR)
                .build());
    }
}
