package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDataChangeDO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MasterDataChangeMapper extends BaseMapperX<MasterDataChangeDO> {

    /** 只从成功生效的持久变更聚合维护时间，忽略审批中、失败、删除及其他模型。 */
    default List<MasterDataChangeDO> selectLatestAppliedByModels(List<Long> modelIds) {
        if (modelIds.isEmpty()) {
            return List.of();
        }
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MasterDataChangeDO>()
                .select("model_id", "MAX(applied_at) AS applied_at")
                .in("model_id", modelIds).eq("status", "APPLIED").groupBy("model_id"));
    }

    /**
     * 按请求查询主数据变更。
     */
    default MasterDataChangeDO selectByRequest(String requestId) {
        return selectOne(MasterDataChangeDO::getRequestId, requestId);
    }

    /**
     * 按指定条件读取并锁定主数据变更记录，供事务内一致性校验或修改使用。
     */
    default MasterDataChangeDO selectForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MasterDataChangeDO>()
                .eq(MasterDataChangeDO::getId, id).last("FOR UPDATE"));
    }

    /**
     * 查询有效。
     */
    default List<MasterDataChangeDO> selectActive(Long modelId) {
        return selectList(new LambdaQueryWrapperX<MasterDataChangeDO>()
                .eq(MasterDataChangeDO::getModelId, modelId)
                .notIn(MasterDataChangeDO::getStatus, List.of("APPLIED", "REJECTED", "CANCELLED")));
    }

    /**
     * 查询主数据变更分页。
     */
    default PageResult<MasterDataChangeDO> selectPage(MasterApprovalPageReqVO req) {
        return selectPage(req, new LambdaQueryWrapperX<MasterDataChangeDO>()
                .eqIfPresent(MasterDataChangeDO::getModelId, req.getModelId())
                .eqIfPresent(MasterDataChangeDO::getStatus, req.getStatus())
                .eqIfPresent(MasterDataChangeDO::getOperation, req.getOperation())
                .and(w -> w.eq(MasterDataChangeDO::getApprovalRequired, true)
                        .or().in(MasterDataChangeDO::getStatus, List.of("UNKNOWN", "APPLYING", "APPROVED")))
                .orderByDesc(MasterDataChangeDO::getId));
    }
}
