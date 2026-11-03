package cn.iocoder.yudao.framework.dbswitch.product.mariadb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMariaDBImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.MysqlDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mysql.MySqlWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mysql.MySqlDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由具体产品负责的实现；能力声明不等于数据库认证。 */
public final class MariadbProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.MARIADB)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseMariaDBImpl::new)
                .reader(source -> new MysqlDatabaseOperator(source, MariadbProfile.INSTANCE))
                .writer(WriteMode.INSERT, MySqlWriterImpl::new)
                .upsert(source -> new cn.iocoder.yudao.framework.dbswitch.dbwriter.mysql.MySqlUpsertWriter(source,
                        MariadbProfile.INSTANCE))
                .synchronizer(MySqlDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "MARIADB_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "INNODB_MONOTONIC_WATERMARK_V2_EXACT_KEYS")
                .typeMapper(new cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlFamilyLogicalTypeMapper(
                        cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlFamilyLogicalTypeMapper.Flavor.MARIADB))
                .cursorTargets(ProductTypeEnum.MARIADB, ProductTypeEnum.MYSQL, ProductTypeEnum.POSTGRESQL)
                .build());
    }
}
