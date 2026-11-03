package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanOrgAssigneeDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-计划组织默认填报人 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillPlanOrgAssigneeMapper extends BaseMapperX<FillPlanOrgAssigneeDO> {

    /**
     * 按计划编号查询填报计划组织填报人列表。
     *
     * @param planId 计划编号
     * @return 按计划编号查询填报计划组织填报人列表结果集合
     */
    default List<FillPlanOrgAssigneeDO> selectListByPlanId(Long planId) {
        return selectList(FillPlanOrgAssigneeDO::getPlanId, planId);
    }

    /**
     * 按计划组织编号查询填报计划组织填报人列表。
     *
     * @param planOrgId 计划组织编号
     * @return 按计划组织编号查询填报计划组织填报人列表结果集合
     */
    default List<FillPlanOrgAssigneeDO> selectListByPlanOrgId(Long planOrgId) {
        return selectList(FillPlanOrgAssigneeDO::getPlanOrgId, planOrgId);
    }

    /**
     * 按计划编号删除填报计划组织填报人。
     *
     * @param planId 计划编号
     */
    default void deleteByPlanId(Long planId) {
        delete(new LambdaQueryWrapperX<FillPlanOrgAssigneeDO>()
                .eq(FillPlanOrgAssigneeDO::getPlanId, planId));
    }

}
