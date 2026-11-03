package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionCreateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.*;
import cn.iocoder.yudao.module.dataforge.enums.fill.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-数据更正服务实现类
 *
 * 更正复用整改链路：不复制数据、不新建工作项——提交冻结后草稿内容与快照一致，
 * 实例从"已合并"CAS 回"整改中"即重新开放同一份数据；重新提交自动标记为更正类型
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class FillCorrectionServiceImpl implements FillCorrectionService {

    @Resource
    private FillCorrectionMapper correctionMapper;

    @Resource
    private FillSubmissionMapper submissionMapper;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillAssigneeMapper assigneeMapper;

    @Resource
    private FillTableVersionService tableVersionService;

    @Resource
    private FillNotifyService notifyService;

    /**
     * 对已合并的提交快照发起数据更正
     *
     * @param reqVO 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCorrection(FillCorrectionCreateReqVO reqVO) {
        // 1. 校验快照与实例状态：仅最新已合并快照可更正
        FillSubmissionDO submission = submissionMapper.selectById(reqVO.getSubmissionId());
        if (submission == null) {
            throw exception(FILL_SUBMISSION_NOT_EXISTS);
        }
        FillWorkItemDO item = workItemMapper.selectById(submission.getWorkItemId());
        if (item == null || !FillWorkItemStatusEnum.MERGED.getStatus().equals(item.getStatus())) {
            throw exception(FILL_CORRECTION_ONLY_MERGED);
        }
        if (!Objects.equals(item.getCurrentSubmissionId(), submission.getId())) {
            throw exception(FILL_CORRECTION_NOT_LATEST);
        }
        // 2. APPEND 策略不支持幂等覆盖，禁止更正
        FillTableVersionDO version = tableVersionService.getTableVersion(submission.getTableVersionId());
        if (FillMergeStrategyEnum.APPEND.getStrategy().equals(version.getMergeStrategy())) {
            throw exception(FILL_CORRECTION_APPEND_UNSUPPORTED);
        }
        // 3. 防重复：一个实例同时只允许一个进行中的更正
        if (correctionMapper.selectActiveByWorkItemId(item.getId()) != null) {
            throw exception(FILL_CORRECTION_EXISTS_ACTIVE);
        }

        // 4. 建更正实例 + 工作项 CAS 已合并 → 整改中（草稿即上次提交内容，无需复制）
        FillCorrectionDO correction = FillCorrectionDO.builder()
                .originSubmissionId(submission.getId())
                .workItemId(item.getId())
                .taskId(item.getTaskId())
                .reason(reqVO.getReason())
                .status(FillCorrectionStatusEnum.DRAFT.getStatus())
                .build();
        correctionMapper.insert(correction);
        int moved = workItemMapper.compareAndSetStatus(item.getId(),
                FillWorkItemStatusEnum.MERGED.getStatus(), FillWorkItemStatusEnum.REWORKING.getStatus());
        if (moved == 0) {
            throw exception(FILL_CORRECTION_ONLY_MERGED);
        }

        // 5. 通知当前有效填报人整改
        FillTaskDO task = taskMapper.selectById(item.getTaskId());
        List<Long> userIds = convertList(assigneeMapper.selectValidListByWorkItemId(item.getId()),
                FillAssigneeDO::getUserId);
        notifyService.sendToUsers(FillNotifyTypeEnum.CORRECTION_CREATED, task, item, userIds,
                java.util.Map.of("reason", reqVO.getReason()));
        return correction.getId();
    }

    /**
     * 取消更正（仅草拟中、尚未重新提交时）：实例状态恢复为已合并
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelCorrection(Long id) {
        FillCorrectionDO correction = correctionMapper.selectById(id);
        if (correction == null) {
            throw exception(FILL_CORRECTION_NOT_EXISTS);
        }
        if (!FillCorrectionStatusEnum.DRAFT.getStatus().equals(correction.getStatus())) {
            throw exception(FILL_CORRECTION_STATUS_INVALID);
        }
        // 实例从整改中恢复为已合并；已被重新提交（不在整改中）则不允许取消
        int moved = workItemMapper.compareAndSetStatus(correction.getWorkItemId(),
                FillWorkItemStatusEnum.REWORKING.getStatus(), FillWorkItemStatusEnum.MERGED.getStatus());
        if (moved == 0) {
            throw exception(FILL_CORRECTION_STATUS_INVALID);
        }
        FillCorrectionDO update = new FillCorrectionDO();
        update.setId(id);
        update.setStatus(FillCorrectionStatusEnum.CANCELLED.getStatus());
        correctionMapper.updateById(update);
    }

    /**
     * 更正分页
     *
     * @param pageReqVO 分页查询条件
     * @return 更正分页结果
     */
    @Override
    public PageResult<FillCorrectionDO> getCorrectionPage(FillCorrectionPageReqVO pageReqVO) {
        return correctionMapper.selectPage(pageReqVO, pageReqVO.getTaskId(), pageReqVO.getStatus());
    }

    /**
     * 是否存在草拟中的更正（提交类型需强制为"更正"）
     *
     * @param workItemId 工作项编号
     * @return 判断结果
     */
    @Override
    public boolean hasActiveCorrection(Long workItemId) {
        FillCorrectionDO correction = correctionMapper.selectActiveByWorkItemId(workItemId);
        return correction != null && FillCorrectionStatusEnum.DRAFT.getStatus().equals(correction.getStatus());
    }

    /**
     * 提交钩子：工作项存在草拟中的更正时绑定新快照并转审批中
     *
     * @param workItemId 工作项编号
     * @param submissionId 提交编号
     * @return 处理是否成功
     */
    @Override
    public boolean bindSubmissionIfCorrecting(Long workItemId, Long submissionId) {
        FillCorrectionDO correction = correctionMapper.selectActiveByWorkItemId(workItemId);
        if (correction == null || !FillCorrectionStatusEnum.DRAFT.getStatus().equals(correction.getStatus())) {
            return false;
        }
        FillCorrectionDO update = new FillCorrectionDO();
        update.setId(correction.getId());
        update.setCorrectionSubmissionId(submissionId);
        update.setStatus(FillCorrectionStatusEnum.APPROVING.getStatus());
        correctionMapper.updateById(update);
        return true;
    }

    /**
     * 合并成功钩子：更正快照合并完成后闭环更正实例
     *
     * @param submissionId 提交编号
     */
    @Override
    public void completeBySubmissionId(Long submissionId) {
        FillCorrectionDO correction = correctionMapper.selectByCorrectionSubmissionId(submissionId);
        if (correction == null || FillCorrectionStatusEnum.MERGED.getStatus().equals(correction.getStatus())) {
            return;
        }
        FillCorrectionDO update = new FillCorrectionDO();
        update.setId(correction.getId());
        update.setStatus(FillCorrectionStatusEnum.MERGED.getStatus());
        correctionMapper.updateById(update);
    }

}
