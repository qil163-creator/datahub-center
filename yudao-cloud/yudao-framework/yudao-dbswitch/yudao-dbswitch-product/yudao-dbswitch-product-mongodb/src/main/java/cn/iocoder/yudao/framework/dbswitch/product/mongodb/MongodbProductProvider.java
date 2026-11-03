package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMongoDBImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.MongoDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mongodb.MongoUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mongodb.MongoWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.mongodb.MongoDatabaseSyncImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class MongodbProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.MONGODB)
                .connect(new MongoConnectionProbe())
                .metadata(DatabaseMongoDBImpl::new)
                .reader(MongoDatabaseOperator::new)
                .writer(WriteMode.INSERT, MongoWriterImpl::new)
                .synchronizer(MongoDatabaseSyncImpl::new)
                .upsert(MongoUpsertWriter::new)
                // 此产品未声明关系型 DDL 支持。
                .declare(DatabaseOperation.SYNC_FULL)
                .declare(DatabaseOperation.WRITE_UPSERT, "MONGODB_SINGLE_ID_TYPED_UPSERT_MAJORITY_JOURNALED")
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "MONGODB_PINNED_FIXED_UPPER_SINGLE_TYPE_WATERMARK_GROUPS_WITHOUT_SNAPSHOT")
                .unsupported(DatabaseOperation.DDL_ALTER, "SyntaxError")
                .cursorTargets(ProductTypeEnum.MONGODB)
                .build());
    }
}
