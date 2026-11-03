package cn.iocoder.yudao.framework.dbswitch.product.oracle;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOracleImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.OracleDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oracle.OracleWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.oracle.OracleDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class OracleProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.ORACLE)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseOracleImpl::new)
                .reader(OracleDatabaseOperator::new)
                .writer(WriteMode.INSERT, OracleWriterImpl::new)
                .upsert(cn.iocoder.yudao.framework.dbswitch.dbwriter.oracle.OracleUpsertWriter::new)
                .synchronizer(OracleDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "ORACLE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "ORACLE23AI_FREE_PINNED_SERIALIZABLE_ROW_SHARE_EXACT_KEYS")
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "ORACLE23AI_SINGLE_UNIQUE_KEY_DERIVED_TABLE_MERGE_EXACT_VALUES")
                .typeMapper(new OracleFamilyLogicalTypeMapper(OracleFamilyLogicalTypeMapper.Flavor.ORACLE))
                .cursorTargets(ProductTypeEnum.ORACLE)
                .build());
    }
}
