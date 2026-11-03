package cn.iocoder.yudao.framework.dbswitch.product.oceanbase;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.OceanbaseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oceanbase.OceanbaseWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.oceanbase.OceanbaseDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class OceanbaseProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.OCEANBASE)
                .connect((request, opener) -> {
                    try (var connection = opener.open()) {
                        boolean mysql = cn.iocoder.yudao.framework.dbswitch.common.util.OceanbaseUtils.isOceanBaseUseMysqlMode(
                                connection);
                        JdbcConnectionProbe.fixedSql(connection,
                                (mysql ? ProductTypeEnum.MYSQL : ProductTypeEnum.ORACLE).getTestSql(),
                                (int) request.timeout().toSeconds());
                    }
                })
                .metadata(DatabaseOceanbaseImpl::new)
                .reader(OceanbaseDatabaseOperator::new)
                .writer(WriteMode.INSERT, OceanbaseWriterImpl::new)
                .upsert(cn.iocoder.yudao.framework.dbswitch.dbwriter.oceanbase.OceanbaseModeUpsertWriter::new)
                .synchronizer(OceanbaseDatabaseSyncImpl::new)
                .typeMapper(new OceanbaseLogicalTypeMapper())
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "OCEANBASE_OBSERVED_MODE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "OCEANBASE_TENANT_PINNED_MODE_SPECIFIC_SNAPSHOT_EXACT_KEYS")
                .declare(DatabaseOperation.WRITE_UPSERT, "OCEANBASE_TENANT_MODE_SPECIFIC_EXACT_VALUE_UPSERT")
                .cursorTargets(ProductTypeEnum.OCEANBASE, ProductTypeEnum.POSTGRESQL)
                .build());
    }
}
