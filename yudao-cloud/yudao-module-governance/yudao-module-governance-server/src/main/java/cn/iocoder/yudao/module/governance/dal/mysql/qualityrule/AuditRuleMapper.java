package cn.iocoder.yudao.module.governance.dal.mysql.qualityrule;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDO;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;

/**
 * 稽查规则 Mapper
 *
 * @author Li
 */
@Mapper
public interface AuditRuleMapper extends BaseMapperX<AuditRuleDO> {

    /**
     * 查询审核规则分页。
     */
    default PageResult<AuditRuleDO> selectPage(AuditRulePageReqVO reqVO, Collection<Long> dimensionIds) {
        LambdaQueryWrapperX<AuditRuleDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.inIfPresent(AuditRuleDO::getDimensionId, dimensionIds)
                .eqIfPresent(AuditRuleDO::getStatus, reqVO.getStatus());
        if (StrUtil.isNotBlank(reqVO.getName())) {
            wrapper.like(AuditRuleDO::getName, reqVO.getName());
        }
        if (StrUtil.isNotBlank(reqVO.getDescription())) {
            wrapper.like(AuditRuleDO::getDescription, reqVO.getDescription());
        }
        // 排序仅映射到静态列引用，客户端字段名不进入 SQL。
        boolean ascending = "asc".equals(reqVO.getSortOrder());
        if (!"id".equals(reqVO.getSortField())) {
            wrapper.orderBy(true, ascending, AuditRuleDO::getCreateTime);
        }
        wrapper.orderBy(true, ascending, AuditRuleDO::getId);
        return selectPage(reqVO, wrapper);
    }

    /**
     * 按名称查询审核规则。
     */
    default AuditRuleDO selectByName(String name) {
        return selectOne(new LambdaQueryWrapperX<AuditRuleDO>().eq(AuditRuleDO::getName, name));
    }

    /**
     * 按维度编号查询审核规则数量。
     */
    default Long selectCountByDimensionIds(Collection<Long> dimensionIds) {
        if (CollUtil.isEmpty(dimensionIds)) {
            return 0L;
        }
        return selectCount(new LambdaQueryWrapperX<AuditRuleDO>().in(AuditRuleDO::getDimensionId, dimensionIds));
    }

}
