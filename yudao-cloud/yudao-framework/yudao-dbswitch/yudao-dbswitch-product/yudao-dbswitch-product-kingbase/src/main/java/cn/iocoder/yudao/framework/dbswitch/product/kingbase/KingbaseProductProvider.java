package cn.iocoder.yudao.framework.dbswitch.product.kingbase;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseKingbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.KingbaseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.kingbase.KingbaseInsertWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.kingbase.KingbaseUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.kingbase.KingbaseDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由产品自身提供的实现；声明不等于数据库认证。 */
public final class KingbaseProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.KINGBASE)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseKingbaseImpl::new)
                .reader(KingbaseDatabaseOperator::new)
                .writer(WriteMode.INSERT, KingbaseInsertWriterImpl::new)
                .upsert(KingbaseUpsertWriter::new)
                .synchronizer(KingbaseDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "KINGBASE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "KINGBASE_ORACLE_CI_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET")
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "KINGBASE_ORACLE_CI_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED")
                .typeMapper(new cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleFamilyLogicalTypeMapper(
                        cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleFamilyLogicalTypeMapper.Flavor.KINGBASE))
                .cursorTargets(ProductTypeEnum.KINGBASE)
                .build());
    }
}
