package cn.iocoder.yudao.framework.dbswitch.product.tidb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.MysqlDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mysql.MySqlWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mysql.MySqlDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由具体产品负责的实现；能力声明不等于数据库认证。 */
public final class TidbProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.TIDB)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseTidbImpl::new)
                .reader(source -> new MysqlDatabaseOperator(source, TidbProfile.INSTANCE))
                .writer(WriteMode.INSERT, MySqlWriterImpl::new)
                .upsert(source -> new cn.iocoder.yudao.framework.dbswitch.dbwriter.mysql.MySqlUpsertWriter(source,
                        TidbProfile.INSTANCE))
                .synchronizer(MySqlDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "TIDB_SNAPSHOT_TSO_MONOTONIC_WATERMARK_V2_EXACT_KEYS")
                .declare(DatabaseOperation.DDL_ALTER, "TIDB_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .typeMapper(new cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlFamilyLogicalTypeMapper(
                        cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlFamilyLogicalTypeMapper.Flavor.TIDB))
                .cursorTargets(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.TIDB)
                .build());
    }
}
