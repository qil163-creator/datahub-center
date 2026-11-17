package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-元数据-库 Mapper
 *
 * @author Li
 */
@Mapper
public interface MetaDbMapper extends BaseMapperX<MetaDbDO> {

    /** 在真实事务内锁定元数据对象，复用现有逻辑删除和数据范围条件。 */
    default MetaDbDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MetaDbDO>()
                .eq(MetaDbDO::getId, id).last("FOR UPDATE"));
    }

    /**
     * 按自然键键查询元数据库。
     */
    default MetaDbDO selectByNaturalKey(Long datasourceId, String dbName, String schemaName) {
        return selectOne(new LambdaQueryWrapperX<MetaDbDO>()
                .eq(MetaDbDO::getDatasourceId, datasourceId)
                .eq(MetaDbDO::getDbName, dbName)
                .eq(MetaDbDO::getSchemaName, schemaName == null ? "" : schemaName)
                .ne(MetaDbDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                .last("LIMIT 1"));
    }

    /**
     * 按指定条件读取并锁定元数据库记录，供事务内一致性校验或修改使用。
     */
    default MetaDbDO selectByNaturalKeyForUpdate(Long datasourceId, String dbName, String schemaName) {
        return selectOne(new LambdaQueryWrapperX<MetaDbDO>()
                .eq(MetaDbDO::getDatasourceId, datasourceId).eq(MetaDbDO::getDbName, dbName)
                .eq(MetaDbDO::getSchemaName, schemaName == null ? "" : schemaName)
                .ne(MetaDbDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                .last("LIMIT 1 FOR UPDATE"));
    }

    /**
     * 按数据源编号查询元数据库列表。
     */
    default List<MetaDbDO> selectListByDatasourceId(Long datasourceId) {
        return selectList(new LambdaQueryWrapperX<MetaDbDO>()
                .eq(MetaDbDO::getDatasourceId, datasourceId)
                .orderByAsc(MetaDbDO::getDbName));
    }

    /**
     * 元数据树数据：全部未删除的库（含待确认删除，前端打警示标）
     */
    default List<MetaDbDO> selectListForTree() {
        // 与默认表列表共用可见状态。最后一次采集的 table_count 在手动删除后可能已过时。
        return selectList(new QueryWrapper<MetaDbDO>()
                .select("id", "datasource_id", "source_system_id", "source_system_name", "db_name", "db_type",
                        "display_snapshot", "object_status",
                        "(SELECT count(*) FROM dh_governance_meta_table t WHERE t.db_id = dh_governance_meta_db.id "
                                + "AND t.deleted = 0 AND t.object_status <> " + MetaDbDO.OBJECT_STATUS_DELETED
                                + ") AS table_count")
                .ne("object_status", MetaDbDO.OBJECT_STATUS_DELETED)
                .orderByAsc("source_system_id", "datasource_id", "db_name"));
    }

}
