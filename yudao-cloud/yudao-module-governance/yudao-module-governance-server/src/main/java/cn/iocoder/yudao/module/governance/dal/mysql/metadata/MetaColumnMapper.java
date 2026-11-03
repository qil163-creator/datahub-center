package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 数据治理-元数据-字段 Mapper
 *
 * @author Li
 */
@Mapper
public interface MetaColumnMapper extends BaseMapperX<MetaColumnDO> {

    /** 在真实事务内锁定元数据对象，复用现有逻辑删除和数据范围条件。 */
    default MetaColumnDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MetaColumnDO>()
                .eq(MetaColumnDO::getId, id).last("FOR UPDATE"));
    }

    /**
     * 按表编号与名称查询元数据字段。
     */
    default MetaColumnDO selectByTableIdAndName(Long tableId, String columnName) {
        return selectOne(new LambdaQueryWrapperX<MetaColumnDO>()
                .eq(MetaColumnDO::getTableId, tableId)
                .eq(MetaColumnDO::getColumnName, columnName)
                .ne(MetaColumnDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                .last("LIMIT 1"));
    }

    /**
     * 按表编号查询元数据字段列表。
     */
    default List<MetaColumnDO> selectListByTableId(Long tableId) {
        return selectList(new LambdaQueryWrapperX<MetaColumnDO>()
                .eq(MetaColumnDO::getTableId, tableId)
                .orderByAsc(MetaColumnDO::getOrdinalPosition)
                .orderByAsc(MetaColumnDO::getId));
    }

    /** 采集事务按稳定顺序锁字段，避免回写过时的平台治理属性。 */
    default List<MetaColumnDO> selectListByTableIdForUpdate(Long tableId) {
        return selectList(new LambdaQueryWrapperX<MetaColumnDO>()
                .eq(MetaColumnDO::getTableId, tableId)
                .ne(MetaColumnDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                .orderByAsc(MetaColumnDO::getId).last("FOR UPDATE"));
    }

    /**
     * 批量回填新插入字段的正式 URN（pending 占位 → urn:datahub:{env}:column:{datasourceId}:{id}），
     * 单条 SQL 完成，避免逐字段 update
     */
    @Update("UPDATE dh_governance_meta_column " +
            "SET resource_urn = 'urn:datahub:' || #{env} || ':column:' || datasource_id || ':' || id " +
            "WHERE table_id = #{tableId} AND resource_urn LIKE 'pending:%'")
    int updatePendingUrnByTableId(@Param("env") String env, @Param("tableId") Long tableId);

    /**
     * 按关键字检索字段（限制条数，避免大库全扫后回表膨胀）
     */
    default List<MetaColumnDO> selectListByKeyword(String keyword, int limit) {
        return selectList(new LambdaQueryWrapperX<MetaColumnDO>()
                .and(inner -> inner.like(MetaColumnDO::getColumnName, keyword)
                        .or().like(MetaColumnDO::getBusinessName, keyword)
                        .or().like(MetaColumnDO::getSourceComment, keyword)
                        .or().like(MetaColumnDO::getCustomComment, keyword))
                .ne(MetaColumnDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                .orderByDesc(MetaColumnDO::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }

}
