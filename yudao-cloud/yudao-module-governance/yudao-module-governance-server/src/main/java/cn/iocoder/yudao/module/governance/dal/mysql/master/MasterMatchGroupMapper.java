package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterMatchGroupPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterMatchGroupDO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mapper
public interface MasterMatchGroupMapper extends BaseMapperX<MasterMatchGroupDO> {

    default PageResult<MasterMatchGroupDO> selectPage(MasterMatchGroupPageReqVO req) {
        return selectPage(req, new LambdaQueryWrapperX<MasterMatchGroupDO>()
                .eq(MasterMatchGroupDO::getModelId, req.getModelId())
                .eqIfPresent(MasterMatchGroupDO::getStatus, req.getStatus())
                .eqIfPresent(MasterMatchGroupDO::getRunId, req.getRunId())
                .orderByDesc(MasterMatchGroupDO::getMaxScore)
                .orderByDesc(MasterMatchGroupDO::getId));
    }

    default MasterMatchGroupDO selectForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MasterMatchGroupDO>()
                .eq(MasterMatchGroupDO::getId, id).last("FOR UPDATE"));
    }

    /** 某状态下的成员集合摘要，用于跳过已忽略或正在合并的同一组 */
    default Set<String> selectFingerprints(Long modelId, List<String> statuses) {
        return selectList(new LambdaQueryWrapperX<MasterMatchGroupDO>()
                .select(MasterMatchGroupDO::getFingerprint)
                .eq(MasterMatchGroupDO::getModelId, modelId)
                .in(MasterMatchGroupDO::getStatus, statuses))
                .stream().map(MasterMatchGroupDO::getFingerprint).collect(Collectors.toSet());
    }

    /** 新一次匹配成功后，旧的待处理组由新结果取代 */
    default int markPendingStale(Long modelId) {
        return update(null, Wrappers.lambdaUpdate(MasterMatchGroupDO.class)
                .eq(MasterMatchGroupDO::getModelId, modelId)
                .eq(MasterMatchGroupDO::getStatus, MasterMatchGroupDO.STATUS_PENDING)
                .set(MasterMatchGroupDO::getStatus, MasterMatchGroupDO.STATUS_STALE));
    }

    /** 合并申请被驳回或取消后，组回到待处理，可重新提交（保留最近一次申请编号供查看） */
    @Update("UPDATE dh_governance_master_match_group g SET status = 'PENDING', update_time = CURRENT_TIMESTAMP "
            + "WHERE g.model_id = #{modelId} AND g.status = 'MERGING' AND g.deleted = 0 AND EXISTS ("
            + "SELECT 1 FROM dh_governance_master_data_change c WHERE c.id = g.change_id "
            + "AND c.status IN ('REJECTED', 'CANCELLED'))")
    int releaseClosedMerges(@Param("modelId") Long modelId);

    /** 合并申请生效：组标记为已合并 */
    default int markMerged(Long groupId, Long changeId, Long actor, LocalDateTime at) {
        return update(null, Wrappers.lambdaUpdate(MasterMatchGroupDO.class)
                .eq(MasterMatchGroupDO::getId, groupId)
                .eq(MasterMatchGroupDO::getChangeId, changeId)
                .eq(MasterMatchGroupDO::getStatus, MasterMatchGroupDO.STATUS_MERGING)
                .set(MasterMatchGroupDO::getStatus, MasterMatchGroupDO.STATUS_MERGED)
                .set(MasterMatchGroupDO::getHandledBy, actor)
                .set(MasterMatchGroupDO::getHandledAt, at));
    }
}
