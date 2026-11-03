package cn.iocoder.yudao.framework.dbswitch.product.sqlite3;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqliteDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlite.Sqlite3WriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.sqlite.Sqlite3DatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class Sqlite3ProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.SQLITE3)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseSqliteImpl::new)
                .reader(SqliteDatabaseOperator::new)
                .writer(WriteMode.INSERT, Sqlite3WriterImpl::new)
                .upsert(cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlite.SqliteUpsertWriter::new)
                .synchronizer(Sqlite3DatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "SQLITE3_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "DEPLOYMENT_PINNED_FILE_INTEGER_WATERMARK_BINARY_KEYS")
                .declare(DatabaseOperation.WRITE_UPSERT, "EXPLICIT_NON_NULL_BINARY_UNIQUE_KEY_TRANSACTIONAL_UPSERT")
                .typeMapper(new Sqlite3LogicalTypeMapper())
                .cursorTargets(ProductTypeEnum.SQLITE3)
                .build());
    }
}
