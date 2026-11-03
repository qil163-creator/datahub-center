package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterCodeCounterDO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MasterCodeCounterMapper extends BaseMapperX<MasterCodeCounterDO> {

    /**
     * 按指定条件读取并锁定主数据编码计数器记录，供事务内一致性校验或修改使用。
     */
    default MasterCodeCounterDO selectForUpdate(Long modelId) {
        return selectOne(new LambdaQueryWrapperX<MasterCodeCounterDO>()
                .eq(MasterCodeCounterDO::getModelId, modelId).last("FOR UPDATE"));
    }
}
