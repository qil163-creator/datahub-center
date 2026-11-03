package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaSearchReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaTablePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 数据治理-元数据-表 Mapper
 *
 * @author Li
 */
@Mapper
public interface MetaTableMapper extends BaseMapperX<MetaTableDO> {

    /** 在真实事务内锁定元数据对象，复用现有逻辑删除和数据范围条件。 */
    default MetaTableDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MetaTableDO>()
                .eq(MetaTableDO::getId, id).last("FOR UPDATE"));
    }

    /**
     * 查询元数据表分页。
     */
    default PageResult<MetaTableDO> selectPage(MetaTablePageReqVO reqVO) {
        LambdaQueryWrapperX<MetaTableDO> wrapper = new LambdaQueryWrapperX<MetaTableDO>()
                .eqIfPresent(MetaTableDO::getDbId, reqVO.getDbId())
                .eqIfPresent(MetaTableDO::getDatasourceId, reqVO.getDatasourceId())
                .eqIfPresent(MetaTableDO::getPublishStatus, reqVO.getPublishStatus())
                .eqIfPresent(MetaTableDO::getObjectStatus, reqVO.getObjectStatus())
                .eqIfPresent(MetaTableDO::getSubjectDomainId, reqVO.getSubjectDomainId());
        wrapper.like(!Boolean.TRUE.equals(reqVO.getFinalized()) && StrUtil.isNotBlank(reqVO.getTableName()),
                MetaTableDO::getTableName, reqVO.getTableName());
        if (Boolean.TRUE.equals(reqVO.getFinalized())) {
            wrapper.isNotNull(MetaTableDO::getPublishedSnapshotId).isNotNull(MetaTableDO::getFinalizedSnapshot);
            if (StrUtil.isNotBlank(reqVO.getTableName())) {
                wrapper.apply("finalized_snapshot->'table'->>'tableName' ILIKE {0}", "%" + reqVO.getTableName() + "%");
            }
            if (StrUtil.isNotBlank(reqVO.getComment())) {
                wrapper.apply("(finalized_snapshot->'table'->>'sourceComment' ILIKE {0} "
                        + "OR finalized_snapshot->'table'->>'customComment' ILIKE {0} "
                        + "OR finalized_snapshot->'table'->>'businessName' ILIKE {0})", "%" + reqVO.getComment() + "%");
            }
        } else if (Boolean.FALSE.equals(reqVO.getFinalized())) {
            wrapper.isNull(MetaTableDO::getPublishedSnapshotId);
        }
        if (Long.valueOf(MetaTablePageReqVO.UNASSIGNED_SOURCE_SYSTEM_ID).equals(reqVO.getSourceSystemId())) {
            wrapper.apply("db_id IN (SELECT id FROM dh_governance_meta_db "
                    + "WHERE source_system_id IS NULL AND deleted = 0)");
        } else if (reqVO.getSourceSystemId() != null) {
            wrapper.apply(
                    "db_id IN (SELECT id FROM dh_governance_meta_db WHERE source_system_id = {0} AND deleted = 0)",
                    reqVO.getSourceSystemId());
        }
        // 默认不展示已删除对象（可显式按状态查询追溯）
        if (reqVO.getObjectStatus() == null) {
            wrapper.ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED);
        }
        // 注释关键字同时匹配源注释/平台注释/业务名称
        if (!Boolean.TRUE.equals(reqVO.getFinalized()) && StrUtil.isNotBlank(reqVO.getComment())) {
            wrapper.and(inner -> inner.like(MetaTableDO::getSourceComment, reqVO.getComment())
                    .or().like(MetaTableDO::getCustomComment, reqVO.getComment())
                    .or().like(MetaTableDO::getBusinessName, reqVO.getComment()));
        }
        wrapper.orderByDesc(MetaTableDO::getId);
        return selectPage(reqVO, wrapper);
    }

    /**
     * 按数据库编号与名称查询元数据表。
     */
    default MetaTableDO selectByDbIdAndName(Long dbId, String tableName) {
        return selectOne(new LambdaQueryWrapperX<MetaTableDO>()
                .eq(MetaTableDO::getDbId, dbId)
                .eq(MetaTableDO::getTableName, tableName)
                .ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                .last("LIMIT 1"));
    }

    /**
     * 按数据源编号与名称查询元数据表。
     */
    default MetaTableDO selectByDatasourceIdAndName(Long datasourceId, String tableName) {
        return selectOne(new LambdaQueryWrapperX<MetaTableDO>()
                .eq(MetaTableDO::getDatasourceId, datasourceId)
                .eq(MetaTableDO::getTableName, tableName)
                .ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                .last("LIMIT 1"));
    }

    /**
     * 按数据源返回全部有效同名表，由调用方核对对象作用域。
     */
    default List<MetaTableDO> selectListByDatasourceIdAndName(Long datasourceId, String tableName) {
        return selectList(new LambdaQueryWrapperX<MetaTableDO>()
                .eq(MetaTableDO::getDatasourceId, datasourceId)
                .eq(MetaTableDO::getTableName, tableName)
                .ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED));
    }

    /**
     * 按数据源编号查询元数据表列表。
     */
    default List<MetaTableDO> selectListByDatasourceId(Long datasourceId) {
        return selectList(new LambdaQueryWrapperX<MetaTableDO>()
                .eq(MetaTableDO::getDatasourceId, datasourceId));
    }

    /**
     * 按数据库编号查询元数据表列表。
     */
    default List<MetaTableDO> selectListByDbId(Long dbId) {
        return selectList(new LambdaQueryWrapperX<MetaTableDO>()
                .eq(MetaTableDO::getDbId, dbId));
    }

    /**
     * 按数据库编号查询元数据表数量。
     */
    default Long selectCountByDbId(Long dbId, Integer objectStatus) {
        return selectCount(new LambdaQueryWrapperX<MetaTableDO>()
                .eq(MetaTableDO::getDbId, dbId)
                .eqIfPresent(MetaTableDO::getObjectStatus, objectStatus));
    }

    /**
     * 检索分页：关键字命中表自身，或落入 extraTableIds（字段命中回表）
     */
    default PageResult<MetaTableDO> selectSearchPage(MetaSearchReqVO reqVO, Collection<Long> extraTableIds) {
        LambdaQueryWrapperX<MetaTableDO> wrapper = new LambdaQueryWrapperX<MetaTableDO>()
                .eqIfPresent(MetaTableDO::getDbId, reqVO.getDbId())
                .eqIfPresent(MetaTableDO::getDatasourceId, reqVO.getDatasourceId())
                .eqIfPresent(MetaTableDO::getLayer, reqVO.getLayer())
                .eqIfPresent(MetaTableDO::getPublishStatus, reqVO.getPublishStatus())
                .eqIfPresent(MetaTableDO::getObjectStatus, reqVO.getObjectStatus())
                .eqIfPresent(MetaTableDO::getSubjectDomainId, reqVO.getSubjectDomainId());
        if (reqVO.getObjectStatus() == null) {
            wrapper.ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED);
        }
        if (Boolean.TRUE.equals(reqVO.getOwnerMissing())) {
            wrapper.and(inner -> inner.isNull(MetaTableDO::getBusinessLeaderUserId)
                    .or().eq(MetaTableDO::getBusinessLeaderUserId, 0L));
        }
        if (StrUtil.isNotBlank(reqVO.getKeyword())) {
            String keyword = reqVO.getKeyword();
            wrapper.and(inner -> {
                inner.like(MetaTableDO::getTableName, keyword)
                        .or().like(MetaTableDO::getBusinessName, keyword)
                        .or().like(MetaTableDO::getSourceComment, keyword)
                        .or().like(MetaTableDO::getCustomComment, keyword);
                if (CollUtil.isNotEmpty(extraTableIds)) {
                    inner.or().in(MetaTableDO::getId, extraTableIds);
                }
            });
        }
        wrapper.orderByDesc(MetaTableDO::getId);
        return selectPage(reqVO, wrapper);
    }

}
