package cn.iocoder.yudao.module.governance.dal.mysql.identify;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.identify.vo.SafetyIdentifyRulePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyIdentifyRuleDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 敏感数据识别规则 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyIdentifyRuleMapper extends BaseMapperX<SafetyIdentifyRuleDO> {

    /**
     * 查询敏感识别规则分页。
     */
    default PageResult<SafetyIdentifyRuleDO> selectPage(SafetyIdentifyRulePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SafetyIdentifyRuleDO>()
                .likeIfPresent(SafetyIdentifyRuleDO::getRuleName, reqVO.getRuleName())
                .eqIfPresent(SafetyIdentifyRuleDO::getMatchType, reqVO.getMatchType())
                .eqIfPresent(SafetyIdentifyRuleDO::getCategoryId, reqVO.getCategoryId())
                .eqIfPresent(SafetyIdentifyRuleDO::getStatus, reqVO.getStatus())
                .orderByAsc(SafetyIdentifyRuleDO::getSort)
                .orderByDesc(SafetyIdentifyRuleDO::getId));
    }

    /**
     * 按规则名称查询敏感识别规则。
     */
    default SafetyIdentifyRuleDO selectByRuleName(String ruleName) {
        return selectOne(new LambdaQueryWrapperX<SafetyIdentifyRuleDO>()
                .eq(SafetyIdentifyRuleDO::getRuleName, ruleName)
                .last("LIMIT 1"));
    }

    /**
     * 查询启用列表。
     */
    default List<SafetyIdentifyRuleDO> selectEnabledList() {
        return selectList(new LambdaQueryWrapperX<SafetyIdentifyRuleDO>()
                .eq(SafetyIdentifyRuleDO::getStatus, SafetyIdentifyRuleDO.STATUS_ENABLE)
                .orderByAsc(SafetyIdentifyRuleDO::getSort)
                .orderByAsc(SafetyIdentifyRuleDO::getId));
    }

}
