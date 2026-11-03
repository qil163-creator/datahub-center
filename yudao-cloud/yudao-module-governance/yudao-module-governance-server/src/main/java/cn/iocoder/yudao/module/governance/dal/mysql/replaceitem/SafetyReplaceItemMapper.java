package cn.iocoder.yudao.module.governance.dal.mysql.replaceitem;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.replaceitem.SafetyReplaceItemDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 脱敏替换明细 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyReplaceItemMapper extends BaseMapperX<SafetyReplaceItemDO> {

    /**
     * 按规则版本编号查询安全替换明细列表。
     */
    default List<SafetyReplaceItemDO> selectListByRuleVerId(Long ruleVerId) {
        return selectList(new LambdaQueryWrapperX<SafetyReplaceItemDO>()
                .eq(SafetyReplaceItemDO::getRuleVerId, ruleVerId)
                .orderByAsc(SafetyReplaceItemDO::getSequenceNo));
    }

}
