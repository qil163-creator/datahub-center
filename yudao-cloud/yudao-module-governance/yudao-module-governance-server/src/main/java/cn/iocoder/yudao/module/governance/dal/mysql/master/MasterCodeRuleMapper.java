package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterCodeRuleDO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MasterCodeRuleMapper extends BaseMapperX<MasterCodeRuleDO> {

    /**
     * 查询主数据编码规则分页。
     */
    default PageResult<MasterCodeRuleDO> selectPage(MasterCodeRulePageReqVO req) {
        return selectPage(req, new LambdaQueryWrapperX<MasterCodeRuleDO>()
                .eqIfPresent(MasterCodeRuleDO::getModelId, req.getModelId())
                .likeIfPresent(MasterCodeRuleDO::getName, req.getName())
                .orderByDesc(MasterCodeRuleDO::getId));
    }

    /**
     * 查询主数据编码规则的启用。
     */
    default MasterCodeRuleDO selectEnabled(Long modelId) {
        return selectOne(new LambdaQueryWrapperX<MasterCodeRuleDO>()
                .eq(MasterCodeRuleDO::getModelId, modelId).eq(MasterCodeRuleDO::getStatus, 0));
    }
}
