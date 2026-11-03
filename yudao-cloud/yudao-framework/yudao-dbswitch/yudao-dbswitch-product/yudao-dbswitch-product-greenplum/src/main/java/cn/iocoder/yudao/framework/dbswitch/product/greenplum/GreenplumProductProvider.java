package cn.iocoder.yudao.framework.dbswitch.product.greenplum;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGreenplumImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.GreenplumDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb.GreenplumCopyWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb.GreenplumInsertWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb.GreenplumUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql.GreenplumDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由产品自身提供的实现；声明不等于数据库认证。 */
public final class GreenplumProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.GREENPLUM)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseGreenplumImpl::new)
                .reader(GreenplumDatabaseOperator::new)
                .writer(WriteMode.COPY, GreenplumCopyWriterImpl::new)
                .writer(WriteMode.INSERT, GreenplumInsertWriterImpl::new)
                .upsert(GreenplumUpsertWriter::new)
                .synchronizer(GreenplumDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "GREENPLUM_HEAP_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "GREENPLUM_HEAP_HASH_PINNED_GENERATION_REPEATABLE_READ_NUMERIC_KEYSET")
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "GREENPLUM_HEAP_HASH_ON_CONFLICT_EXACT_NUMERIC_PRIMARY_KEY_COMMIT_RETURNED")
                .typeMapper(new cn.iocoder.yudao.framework.dbswitch.product.postgresql.PgFamilyLogicalTypeMapper(
                        cn.iocoder.yudao.framework.dbswitch.product.postgresql.PgFamilyLogicalTypeMapper.Flavor.GREENPLUM))
                .cursorTargets(ProductTypeEnum.GREENPLUM)
                .build());
    }
}
