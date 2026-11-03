package cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserver2000Impl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqlServer2000DatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlserver.SqlServer2000UpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mssql.SqlServerDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class Sqlserver2000ProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.SQLSERVER2000)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseSqlserver2000Impl::new)
                .reader(SqlServer2000DatabaseOperator::new)
                .writer(WriteMode.INSERT, SqlServerWriterImpl::new)
                .synchronizer(SqlServerDatabaseSyncImpl::new)
                .upsert(SqlServer2000UpsertWriter::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, Sqlserver2000AddColumn.REASON)
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "SQLSERVER2000_8_00_760_PINNED_GENERATION_LOCKED_NUMERIC_KEYSET")
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "SQLSERVER2000_8_00_760_EXCLUSIVE_LOCK_UPDATE_INSERT_ORDERED_PRIMARY_KEY")
                .typeMapper(new Sqlserver2000LogicalTypeMapper())
                .cursorTargets(ProductTypeEnum.SQLSERVER2000)
                .build());
    }
}
