package cn.iocoder.yudao.module.governance.dal.mysql.standardelement;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;

/**
 * 数据标准--标准数据元 Mapper
 *
 * @author Li
 */
@Mapper
public interface StandardElementMapper extends BaseMapperX<StandardElementDO> {

    /**
     * 查询标准数据元分页。
     */
    default PageResult<StandardElementDO> selectPage(StandardElementPageReqVO reqVO) {
        var query = new LambdaQueryWrapperX<StandardElementDO>()
                .eqIfPresent(StandardElementDO::getDocumentId, reqVO.getDocumentId())
                .eqIfPresent(StandardElementDO::getCode, reqVO.getCode())
                .likeIfPresent(StandardElementDO::getName, reqVO.getName())
                .likeIfPresent(StandardElementDO::getEngName, reqVO.getEngName())
                .inIfPresent(StandardElementDO::getCategoryId, reqVO.getCategoryIds())
                .eqIfPresent(StandardElementDO::getCodeSetId, reqVO.getCodeSetId())
                .eqIfPresent(StandardElementDO::getLifecycleStatus, reqVO.getLifecycleStatus())
                .eqIfPresent(StandardElementDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(StandardElementDO::getCreateTime, reqVO.getCreateTime());
        boolean asc = "asc".equals(reqVO.getSortOrder());
        if ("id".equals(reqVO.getSortField())) {
            query.orderBy(true, asc, StandardElementDO::getId);
        } else {
            query.orderBy(true, asc, StandardElementDO::getCreateTime).orderByDesc(StandardElementDO::getId);
        }
        return selectPage(reqVO, query);
    }

    /**
     * 按名称查询标准数据元。
     */
    default StandardElementDO selectByName(String name) {
        return selectOne(StandardElementDO::getName, name);
    }

    /**
     * 按英文名称查询标准数据元。
     */
    default StandardElementDO selectByEngName(String engName) {
        return selectOne(StandardElementDO::getEngName, engName);
    }

    /**
     * 按编码集合编号查询标准数据元数量。
     */
    default Long selectCountByCodeSetIds(Collection<Integer> codeSetIds) {
        return selectCount(new LambdaQueryWrapperX<StandardElementDO>()
                .in(StandardElementDO::getCodeSetId, codeSetIds));
    }

    /**
     * 按生命周期状态查询标准数据元数量。
     */
    default Long selectCountByLifecycleStatus(String lifecycleStatus) {
        return selectCount(StandardElementDO::getLifecycleStatus, lifecycleStatus);
    }

    /**
     * 按编码集合已绑定查询标准数据元数量。
     */
    default Long selectCountByCodeSetBound() {
        return selectCount(new LambdaQueryWrapperX<StandardElementDO>()
                .isNotNull(StandardElementDO::getCodeSetId));
    }

    /**
     * 按指定条件读取并锁定标准数据元记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_standard_element "
            + "WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    StandardElementDO selectByIdForUpdate(@Param("id") Integer id);

    /**
     * 统计已落标的数据元数量（按数据元去重）。
     * 口径：被元数据字段引用（运行态落标） UNION 建模字段活动落标事实（设计态落标）。
     */
    @Select("SELECT COUNT(*) FROM ("
            + "SELECT DISTINCT standard_element_id AS element_id FROM dh_governance_meta_column "
            + "WHERE standard_element_id IS NOT NULL AND deleted = 0 "
            + "UNION "
            + "SELECT DISTINCT element_id FROM dh_governance_standard_mapping "
            + "WHERE target_type = 'MODEL_FIELD' AND mapping_status IN ('CONFIRMED', 'PROPOSED') "
            + "AND deleted = 0"
            + ") mapped")
    Long selectMappedElementCount();

    /**
     * 统计设计态（建模字段）落标的数据元数量（按数据元去重，仅活动事实）。
     */
    @Select("SELECT COUNT(DISTINCT element_id) FROM dh_governance_standard_mapping "
            + "WHERE target_type = 'MODEL_FIELD' AND mapping_status IN ('CONFIRMED', 'PROPOSED') "
            + "AND deleted = 0")
    Long selectModelMappedElementCount();

}
