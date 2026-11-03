package cn.iocoder.yudao.module.governance.dal.mysql.qualityrule;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRulePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;

/**
 * 清洗规则 Mapper
 *
 * @author Li
 */
@Mapper
public interface CleanRuleMapper extends BaseMapperX<CleanRuleDO> {

    /**
     * 查询清洗规则分页。
     */
    default PageResult<CleanRuleDO> selectPage(CleanRulePageReqVO reqVO, Collection<Long> categoryIds) {
        LambdaQueryWrapperX<CleanRuleDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.inIfPresent(CleanRuleDO::getCategoryId, categoryIds)
                .eqIfPresent(CleanRuleDO::getStatus, reqVO.getStatus());
        if (StrUtil.isNotBlank(reqVO.getName())) {
            wrapper.like(CleanRuleDO::getName, reqVO.getName());
        }
        if (StrUtil.isNotBlank(reqVO.getDescription())) {
            wrapper.like(CleanRuleDO::getDescription, reqVO.getDescription());
        }
        // 排序仅映射到静态列引用，客户端字段名不进入 SQL。
        boolean ascending = "asc".equals(reqVO.getSortOrder());
        if (!"id".equals(reqVO.getSortField())) {
            wrapper.orderBy(true, ascending, CleanRuleDO::getCreateTime);
        }
        wrapper.orderBy(true, ascending, CleanRuleDO::getId);
        return selectPage(reqVO, wrapper);
    }

    /**
     * 按名称查询清洗规则。
     */
    default CleanRuleDO selectByName(String name) {
        return selectOne(new LambdaQueryWrapperX<CleanRuleDO>().eq(CleanRuleDO::getName, name));
    }

    /**
     * 按分类编号查询清洗规则数量。
     */
    default Long selectCountByCategoryIds(Collection<Long> categoryIds) {
        if (CollUtil.isEmpty(categoryIds)) {
            return 0L;
        }
        return selectCount(new LambdaQueryWrapperX<CleanRuleDO>().in(CleanRuleDO::getCategoryId, categoryIds));
    }

}
