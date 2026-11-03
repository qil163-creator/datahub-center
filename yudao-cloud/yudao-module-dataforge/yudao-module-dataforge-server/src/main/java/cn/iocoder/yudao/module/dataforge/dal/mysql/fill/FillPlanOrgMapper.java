package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanOrgDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-计划目标组织 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillPlanOrgMapper extends BaseMapperX<FillPlanOrgDO> {

    /**
     * 按计划编号查询填报计划组织列表。
     *
     * @param planId 计划编号
     * @return 按计划编号查询填报计划组织列表结果集合
     */
    default List<FillPlanOrgDO> selectListByPlanId(Long planId) {
        return selectList(new LambdaQueryWrapperX<FillPlanOrgDO>()
                .eq(FillPlanOrgDO::getPlanId, planId)
                .orderByAsc(FillPlanOrgDO::getSort));
    }

    /**
     * 按计划编号删除填报计划组织。
     *
     * @param planId 计划编号
     */
    default void deleteByPlanId(Long planId) {
        delete(new LambdaQueryWrapperX<FillPlanOrgDO>()
                .eq(FillPlanOrgDO::getPlanId, planId));
    }

}
