package cn.iocoder.yudao.module.governance.dal.mysql.rule;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRulePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 脱敏规则（主表）；参数演进由版本表承载 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyRuleMapper extends BaseMapperX<SafetyRuleDO> {

    /**
     * 查询脱敏规则分页。
     */
    default PageResult<SafetyRuleDO> selectPage(SafetyRulePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SafetyRuleDO>()
                .likeIfPresent(SafetyRuleDO::getRuleName, reqVO.getRuleName())
                .eqIfPresent(SafetyRuleDO::getSourceType, reqVO.getSourceType())
                .eqIfPresent(SafetyRuleDO::getStatus, reqVO.getStatus())
                .orderByDesc(SafetyRuleDO::getId));
    }

    /**
     * 按指定条件读取并锁定脱敏规则记录，供事务内一致性校验或修改使用。
     */
    default SafetyRuleDO selectForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<SafetyRuleDO>()
                .eq(SafetyRuleDO::getId, id)
                .last("FOR UPDATE"));
    }

}