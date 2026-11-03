package cn.iocoder.yudao.framework.dbswitch.product.opengauss;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOpengaussImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.OpengaussDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.opengauss.OpengaussInsertWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.opengauss.OpengaussDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由产品自身提供的实现；声明不等于数据库认证。 */
public final class OpengaussProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.OPENGAUSS)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseOpengaussImpl::new)
                .reader(OpengaussDatabaseOperator::new)
                .writer(WriteMode.INSERT, OpengaussInsertWriterImpl::new)
                .upsert(cn.iocoder.yudao.framework.dbswitch.dbwriter.opengauss.OpengaussUpsertWriter::new)
                .synchronizer(OpengaussDatabaseSyncImpl::new)
                .declare(DatabaseOperation.DDL_CREATE)
                .declare(DatabaseOperation.DDL_ALTER, "OPENGAUSS_A_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED")
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.SYNC_CURSOR, "OPENGAUSS_NATIVE_GENERATION_REPEATABLE_READ_NUMERIC_KEYS")
                .declare(DatabaseOperation.WRITE_UPSERT, "OPENGAUSS_EXPLICIT_NUMERIC_PRIMARY_KEY_TRANSACTIONAL_MERGE")
                .typeMapper(new OpengaussLogicalTypeMapper())
                .cursorTargets(ProductTypeEnum.OPENGAUSS, ProductTypeEnum.POSTGRESQL)
                .build());
    }
}
