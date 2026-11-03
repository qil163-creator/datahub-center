package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseInfluxdbImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.InfluxdbDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.influxdb.InfluxUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.influxdb.InfluxdbWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.influxdb.InfluxdbDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 由产品拥有的实现；声明不代表数据库认证。 */
public final class Influxdb3ProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.INFLUXDB3)
                .connect(JdbcConnectionProbe.standard())
                .metadata(DatabaseInfluxdbImpl::new)
                .reader(InfluxdbDatabaseOperator::new)
                .writer(WriteMode.INSERT, InfluxdbWriterImpl::new)
                .upsert(InfluxUpsertWriter::new)
                // 有意不提供部分变更或空操作变更路径。
                // 创建使用原生管理 API，绝不使用关系型 SQL DDL。
                .declare(DatabaseOperation.DDL_CREATE,
                        "NATIVE_CORE_TABLE_MANAGEMENT_REQUIRES_DATABASE_CERTIFICATION")
                .declare(DatabaseOperation.SYNC_FULL)
                .synchronizer(InfluxdbDatabaseSyncImpl::new)
                .declare(DatabaseOperation.SYNC_DIFF,
                        "DURABLE_BOUNDED_WHOLE_TABLE_REWRITE_WITH_EXCLUSIVE_OWNERSHIP")
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "INFLUX_PINNED_FIXED_UPPER_NANOSECOND_TAGSET_KEYSET_WITHOUT_SNAPSHOT")
                .declare(DatabaseOperation.WRITE_UPSERT,
                        "INFLUX_NATIVE_TAGSET_TIME_FIELD_MERGE_REQUEST_ACKNOWLEDGED")
                .typeMapper(new InfluxDb3LogicalTypeMapper())
                .cursorTargets(ProductTypeEnum.INFLUXDB3)
                .build());
    }
}
