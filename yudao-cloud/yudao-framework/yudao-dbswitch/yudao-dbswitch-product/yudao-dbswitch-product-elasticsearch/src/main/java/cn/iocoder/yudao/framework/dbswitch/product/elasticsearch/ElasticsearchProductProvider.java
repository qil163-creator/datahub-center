package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseElasticsearchImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.ElasticsearchDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.elasticsearch.ElasticsearchWriterImpl;

import java.util.Collection;
import java.util.List;

/** 产品持有的实现；声明不等于数据库认证。 */
public final class ElasticsearchProductProvider implements DbSwitchProductProvider {

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.ELASTICSEARCH)
                .connect(new ElasticsearchConnectionProbe())
                .metadata(DatabaseElasticsearchImpl::new)
                .reader(ElasticsearchDatabaseOperator::new)
                .writer(WriteMode.INSERT, ElasticsearchWriterImpl::new)
                .synchronizer(
                        cn.iocoder.yudao.framework.dbswitch.dbsynch.elasticsearch.ElasticsearchDatabaseSyncImpl::new)
                .upsert(cn.iocoder.yudao.framework.dbswitch.dbwriter.elasticsearch.ElasticsearchUpsertWriter::new)
                // 原生索引规划独立于关系型 SQL DDL；不构成认证。
                .declare(DatabaseOperation.SYNC_FULL)
                // 仅限单个物理索引、已映射的可排序水位，以及单个 _id upsert 键；无快照。
                .declare(DatabaseOperation.SYNC_CURSOR,
                        "ELASTICSEARCH_PINNED_FIXED_UPPER_SINGLE_TYPE_WATERMARK_GROUPS_WITHOUT_SNAPSHOT")
                .declare(DatabaseOperation.WRITE_UPSERT, "ELASTICSEARCH_SINGLE_ID_INDEX_UPSERT_BULK_ACKNOWLEDGED")
                // jdbc-jest 在 HTTP 调用前将语句解析为 JSON。ALTER 不是 JSON。
                .unsupported(DatabaseOperation.DDL_ALTER, "JSON_PARSE_EXCEPTION")
                .cursorTargets(ProductTypeEnum.ELASTICSEARCH)
                .build());
    }
}
