package cn.iocoder.yudao.module.governance.dal.mysql.standardelement;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementRuleDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface StandardElementRuleMapper extends BaseMapperX<StandardElementRuleDO> {

    /**
     * 按所属人查询标准数据元规则。
     */
    default List<StandardElementRuleDO> selectByOwner(Integer id) {
        return selectList(new LambdaQueryWrapperX<StandardElementRuleDO>().eq(StandardElementRuleDO::getElementId, id)
                .orderByAsc(StandardElementRuleDO::getId));
    }
}
