package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableSnapshotDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-元数据-表结构快照 Mapper
 *
 * @author Li
 */
@Mapper
public interface MetaTableSnapshotMapper extends BaseMapperX<MetaTableSnapshotDO> {

    /**
     * 按表编号与版本查询元数据表快照。
     */
    default MetaTableSnapshotDO selectByTableIdAndVersion(Long tableId, String structVersion) {
        return selectOne(new LambdaQueryWrapperX<MetaTableSnapshotDO>()
                .eq(MetaTableSnapshotDO::getTableId, tableId)
                .eq(MetaTableSnapshotDO::getStructVersion, structVersion)
                .last("LIMIT 1"));
    }

    /**
     * 按表编号查询元数据表快照列表。
     */
    default List<MetaTableSnapshotDO> selectListByTableId(Long tableId) {
        return selectList(new LambdaQueryWrapperX<MetaTableSnapshotDO>()
                .eq(MetaTableSnapshotDO::getTableId, tableId)
                .orderByDesc(MetaTableSnapshotDO::getId));
    }

    /**
     * 版本列表（不含快照体，避免大 jsonb 拖慢列表查询；快照体单独按编号获取）
     */
    default List<MetaTableSnapshotDO> selectListByTableIdWithoutBody(Long tableId) {
        return selectList(new LambdaQueryWrapperX<MetaTableSnapshotDO>()
                .select(MetaTableSnapshotDO::getId, MetaTableSnapshotDO::getTableId,
                        MetaTableSnapshotDO::getDbId, MetaTableSnapshotDO::getStructVersion,
                        MetaTableSnapshotDO::getInstanceId, MetaTableSnapshotDO::getChangeSource,
                        MetaTableSnapshotDO::getSnapshotHash, MetaTableSnapshotDO::getChangeSummary,
                        MetaTableSnapshotDO::getBaselineFlag, MetaTableSnapshotDO::getCreateTime)
                .eq(MetaTableSnapshotDO::getTableId, tableId)
                .orderByDesc(MetaTableSnapshotDO::getId));
    }

}
