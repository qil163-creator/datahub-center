package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileLatestRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRunPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRunRespVO;

/**
 * 数据探查 Service。数据范围与元数据表查询一致：平台内全可见，按接口权限控制。
 *
 * @author Li
 */
public interface ProfileRunService {

    /**
     * 发起探查：插入一条 PENDING 记录。同一张表已有 PENDING/RUNNING 时抛出 PROFILE_RUN_ACTIVE。
     *
     * @param metaTableId 元数据表编号
     * @param userId      执行人
     * @return 探查记录编号
     */
    Long createRun(Long metaTableId, Long userId);

    /**
     * 最新结果：最近一次记录（轮询用）+ 最近一次成功记录的字段指标与规则建议。
     */
    ProfileLatestRespVO getLatest(Long metaTableId);

    /**
     * 探查记录分页。
     */
    PageResult<ProfileRunRespVO> getRunPage(ProfileRunPageReqVO reqVO);

    /**
     * 定时任务：恢复失联记录并执行待执行记录，返回本次执行数。
     */
    int processDueRuns();

}
