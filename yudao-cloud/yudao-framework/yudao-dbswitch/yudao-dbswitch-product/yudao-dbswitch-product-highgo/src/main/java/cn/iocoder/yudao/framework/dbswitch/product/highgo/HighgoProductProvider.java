package cn.iocoder.yudao.framework.dbswitch.product.highgo;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHighgoImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.HighgoDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.highgo.HighgoInsertWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.highgo.HighgoUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.highgo.HighgoDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由产品自身提供的实现；声明不等于数据库认证。 */
public final class HighgoProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.HIGHGO)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseHighgoImpl::new)
                .reader(HighgoDatabaseOperator::new)
                .writer(WriteMode.INSERT, HighgoInsertWriterImpl::new)
                .upsert(HighgoUpsertWriter::new)
                .synchronizer(HighgoDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "HIGHGO_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "HIGHGO_6_0_4_2_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET")
                .declare(DatabaseOperation.WRITE_UPSERT, "HIGHGO_6_0_4_2_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED")
                .typeMapper(new cn.iocoder.yudao.framework.dbswitch.product.postgresql.PgFamilyLogicalTypeMapper(
                        cn.iocoder.yudao.framework.dbswitch.product.postgresql.PgFamilyLogicalTypeMapper.Flavor.HIGHGO))
                .cursorTargets(ProductTypeEnum.HIGHGO)
                .build());
    }
}
