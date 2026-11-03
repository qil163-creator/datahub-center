package cn.iocoder.yudao.framework.dbswitch.product.dm;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDmImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.DmDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.dm.DmWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.dm.DmDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由具体产品负责的实现；能力声明不等于数据库认证。 */
public final class DmProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.DM)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseDmImpl::new)
                .reader(DmDatabaseOperator::new)
                .writer(WriteMode.INSERT, DmWriterImpl::new)
                .upsert(cn.iocoder.yudao.framework.dbswitch.dbwriter.dm.DmUpsertWriter::new)
                .synchronizer(DmDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "DM_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "DEPLOYMENT_PINNED_NATIVE_GENERATION_SERIALIZABLE_NUMERIC_KEYS")
                .declare(DatabaseOperation.WRITE_UPSERT, "NON_IDENTITY_NUMERIC_PRIMARY_KEY_TRANSACTIONAL_MERGE")
                .typeMapper(new cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleFamilyLogicalTypeMapper(
                        cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleFamilyLogicalTypeMapper.Flavor.DM))
                .cursorTargets(ProductTypeEnum.DM, ProductTypeEnum.POSTGRESQL)
                .build());
    }
}
