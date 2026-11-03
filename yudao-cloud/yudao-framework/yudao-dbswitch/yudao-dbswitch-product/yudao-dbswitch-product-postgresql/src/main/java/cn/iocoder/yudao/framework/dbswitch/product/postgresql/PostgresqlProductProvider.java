package cn.iocoder.yudao.framework.dbswitch.product.postgresql;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.PostgreSqlDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresInsertWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql.PostgresqlDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCopyWriterImpl;

import java.util.Collection;
import java.util.List;

/** 由产品自身提供的实现；声明不等于数据库认证。 */
public final class PostgresqlProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.POSTGRESQL)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabasePostgresImpl::new)
                .reader(PostgreSqlDatabaseOperator::new)
                .writer(WriteMode.INSERT, PostgresInsertWriterImpl::new)
                .writer(WriteMode.COPY, PostgresCopyWriterImpl::new)
                .upsert(cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresUpsertWriter::new)
                .atomicBatch(cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresAtomicBatchWriter::new)
                .synchronizer(PostgresqlDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "POSTGRESQL_NULLABLE_ADD_WITH_FRESH_LOCKED_PRECONDITION_ONLY")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "MONOTONIC_WATERMARK_V2_NATIVE_OR_EXACT_NUMERIC_KEYS")
                .typeMapper(new PostgresLogicalTypeMapper())
                .cursorTargets(ProductTypeEnum.DM, ProductTypeEnum.MARIADB, ProductTypeEnum.MYSQL,
                        ProductTypeEnum.OCEANBASE, ProductTypeEnum.OPENGAUSS, ProductTypeEnum.POSTGRESQL,
                        ProductTypeEnum.SQLSERVER, ProductTypeEnum.TIDB)
                .build());
    }
}
