package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterMatchRuleDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MasterMatchRuleMapper extends BaseMapperX<MasterMatchRuleDO> {

    default MasterMatchRuleDO selectByModelId(Long modelId) {
        return selectOne(MasterMatchRuleDO::getModelId, modelId);
    }
}
