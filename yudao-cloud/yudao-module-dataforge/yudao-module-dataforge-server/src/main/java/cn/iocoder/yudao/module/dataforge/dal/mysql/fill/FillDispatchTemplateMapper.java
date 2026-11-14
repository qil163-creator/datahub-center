package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillDispatchTemplateDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-中间单位默认下发名单 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillDispatchTemplateMapper extends BaseMapperX<FillDispatchTemplateDO> {

    default List<FillDispatchTemplateDO> selectListByPlanAndParent(Long planId, Long parentOrgId) {
        return selectList(new LambdaQueryWrapperX<FillDispatchTemplateDO>()
                .eq(FillDispatchTemplateDO::getPlanId, planId)
                .eq(FillDispatchTemplateDO::getParentOrgId, parentOrgId)
                .orderByAsc(FillDispatchTemplateDO::getSort)
                .orderByAsc(FillDispatchTemplateDO::getId));
    }

    default List<FillDispatchTemplateDO> selectListByPlanId(Long planId) {
        return selectList(FillDispatchTemplateDO::getPlanId, planId);
    }

    default int deleteByPlanAndParent(Long planId, Long parentOrgId) {
        return delete(new LambdaQueryWrapperX<FillDispatchTemplateDO>()
                .eq(FillDispatchTemplateDO::getPlanId, planId)
                .eq(FillDispatchTemplateDO::getParentOrgId, parentOrgId));
    }

}
