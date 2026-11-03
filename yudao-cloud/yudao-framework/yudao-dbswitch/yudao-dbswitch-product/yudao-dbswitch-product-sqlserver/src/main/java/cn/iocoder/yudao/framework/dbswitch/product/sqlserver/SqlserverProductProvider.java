package cn.iocoder.yudao.framework.dbswitch.product.sqlserver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserverImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqlServerDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mssql.SqlServerDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class SqlserverProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.SQLSERVER)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseSqlserverImpl::new)
                .reader(SqlServerDatabaseOperator::new)
                .writer(WriteMode.INSERT, SqlServerWriterImpl::new)
                .upsert(cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerUpsertWriter::new)
                .synchronizer(SqlServerDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "SQLSERVER_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "SQLSERVER16_SNAPSHOT_MANAGED_GENERATION_TYPED_KEYS")
                .typeMapper(new SqlServerFamilyLogicalTypeMapper(SqlServerFamilyLogicalTypeMapper.Flavor.SQLSERVER))
                .cursorTargets(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.SQLSERVER)
                .build());
    }
}
