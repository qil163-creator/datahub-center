package cn.iocoder.yudao.framework.dbswitch.product.gbase8a;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGbase8aImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.GbaseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mysql.MySqlWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.gbase8a.Gbase8aUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mysql.MySqlDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class Gbase8aProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.GBASE8A)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseGbase8aImpl::new)
                .reader(GbaseDatabaseOperator::new)
                .writer(WriteMode.INSERT, MySqlWriterImpl::new)
                .upsert(Gbase8aUpsertWriter::new)
                .declare(DatabaseOperation.WRITE_UPSERT, "GBASE8A_86243_EXPRESS_LOCKED_SINGLE_MERGE_EXACT_STAGING")
                .synchronizer(MySqlDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "GBASE8A_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "GBASE8A_PINNED_FIXED_UPPER_EXACT_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED")
                .typeMapper(new Gbase8aLogicalTypeMapper())
                .cursorTargets(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.GBASE8A)
                .build());
    }
}
