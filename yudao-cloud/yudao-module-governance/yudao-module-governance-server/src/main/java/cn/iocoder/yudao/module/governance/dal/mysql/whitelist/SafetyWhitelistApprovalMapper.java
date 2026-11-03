package cn.iocoder.yudao.module.governance.dal.mysql.whitelist;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistApprovalDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 脱敏白名单审批记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyWhitelistApprovalMapper extends BaseMapperX<SafetyWhitelistApprovalDO> {

    /**
     * 按白名单编号查询脱敏白名单审批列表。
     */
    default List<SafetyWhitelistApprovalDO> selectListByWhitelistId(Long whitelistId) {
        return selectList(new LambdaQueryWrapperX<SafetyWhitelistApprovalDO>()
                .eq(SafetyWhitelistApprovalDO::getWhitelistId, whitelistId)
                .orderByDesc(SafetyWhitelistApprovalDO::getId));
    }

}
