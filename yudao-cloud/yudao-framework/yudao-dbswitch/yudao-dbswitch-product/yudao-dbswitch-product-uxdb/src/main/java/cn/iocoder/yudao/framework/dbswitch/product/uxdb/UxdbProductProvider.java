package cn.iocoder.yudao.framework.dbswitch.product.uxdb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseUxdbImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.UxdbDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb.UxdbCopyWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb.UxdbUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.uxdb.UxdbDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb.UxdbInsertWriterImpl;

import java.util.Collection;
import java.util.List;

/** 由产品自身提供的实现；声明不等于数据库认证。 */
public final class UxdbProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.UXDB)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseUxdbImpl::new)
                .reader(UxdbDatabaseOperator::new)
                .writer(WriteMode.INSERT, UxdbInsertWriterImpl::new)
                .writer(WriteMode.COPY, UxdbCopyWriterImpl::new)
                .upsert(UxdbUpsertWriter::new)
                .synchronizer(UxdbDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "UXDB_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "UXSINODB_2_1_0_3_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET")
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "UXSINODB_2_1_0_3_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED")
                .typeMapper(new cn.iocoder.yudao.framework.dbswitch.product.postgresql.PgFamilyLogicalTypeMapper(
                        cn.iocoder.yudao.framework.dbswitch.product.postgresql.PgFamilyLogicalTypeMapper.Flavor.UXDB))
                .cursorTargets(ProductTypeEnum.UXDB)
                .build());
    }
}
