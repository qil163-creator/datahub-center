package cn.iocoder.yudao.module.governance.dal.mysql.standardelement;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardMappingDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据标准--落标映射事实 Mapper
 *
 * @author Li
 */
@Mapper
public interface StandardMappingMapper extends BaseMapperX<StandardMappingDO> {

    /**
     * 按指定条件读取并锁定标准映射记录，供事务内一致性校验或修改使用。
     */
    default StandardMappingDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<StandardMappingDO>()
                .eq(StandardMappingDO::getId, id).last("FOR UPDATE"));
    }

    /** 包含已删除字段的映射，执行预检必须显式报告失效，不能悄悄丢弃约束。 */
    @Select("SELECT m.* FROM dh_governance_standard_mapping m "
            + "JOIN dh_governance_meta_column c ON c.id = m.column_id "
            + "WHERE m.deleted = 0 AND m.mapping_status = 'CONFIRMED' "
            + "AND m.target_type = 'METADATA_COLUMN' AND c.deleted = 0 AND c.object_status = 1 "
            + "AND c.table_id = #{tableId} ORDER BY m.id")
    List<StandardMappingDO> selectConfirmedMetadataListByTableId(@Param("tableId") Long tableId);

    /**
     * 按数据元编号查询标准映射列表。
     */
    default List<StandardMappingDO> selectListByElementId(Integer elementId) {
        return selectList(new LambdaQueryWrapperX<StandardMappingDO>()
                .eq(StandardMappingDO::getElementId, elementId)
                .orderByDesc(StandardMappingDO::getId));
    }

    /**
     * 按数据元编号与字段编号查询标准映射。
     */
    default StandardMappingDO selectByElementIdAndColumnId(Integer elementId, Long columnId) {
        return selectOne(new LambdaQueryWrapperX<StandardMappingDO>()
                .eq(StandardMappingDO::getElementId, elementId)
                .eq(StandardMappingDO::getColumnId, columnId)
                .orderByDesc(StandardMappingDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 查询指定目标类型的活动落标事实（CONFIRMED / PROPOSED）。
     */
    default List<StandardMappingDO> selectActiveListByTargetType(String targetType) {
        return selectList(new LambdaQueryWrapperX<StandardMappingDO>()
                .eq(StandardMappingDO::getTargetType, targetType)
                .in(StandardMappingDO::getMappingStatus,
                        List.of(StandardMappingDO.STATUS_CONFIRMED, StandardMappingDO.STATUS_PROPOSED)));
    }

    /**
     * 按指定条件读取并锁定标准映射记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_standard_mapping "
            + "WHERE target_type = #{targetType} AND target_urn = #{targetUrn} "
            + "AND mapping_status = 'CONFIRMED' "
            + "AND deleted = 0 ORDER BY id DESC LIMIT 1 FOR UPDATE")
    StandardMappingDO selectConfirmedByTargetForUpdate(@Param("targetType") String targetType,
            @Param("targetUrn") String targetUrn);

}
