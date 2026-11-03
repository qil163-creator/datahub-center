package cn.iocoder.yudao.framework.dbswitch.product.mysql;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.MysqlDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mysql.MySqlWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mysql.MySqlDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由具体产品负责的实现；能力声明不等于数据库认证。 */
public final class MysqlProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.MYSQL)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseMysqlImpl::new)
                .reader(MysqlDatabaseOperator::new)
                .writer(WriteMode.INSERT, MySqlWriterImpl::new)
                .upsert(cn.iocoder.yudao.framework.dbswitch.dbwriter.mysql.MySqlUpsertWriter::new)
                .synchronizer(MySqlDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "MYSQL_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "INNODB_MONOTONIC_WATERMARK_V2_EXACT_KEYS")
                .typeMapper(new MysqlFamilyLogicalTypeMapper(MysqlFamilyLogicalTypeMapper.Flavor.MYSQL))
                .cursorTargets(ProductTypeEnum.MARIADB, ProductTypeEnum.MYSQL, ProductTypeEnum.POSTGRESQL)
                .build());
    }
}
