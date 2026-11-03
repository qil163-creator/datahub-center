package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmissionPreviewRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmissionRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmitReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillSubmitRespVO;

import java.util.List;

/**
 * 在线填报-提交服务接口（草稿冻结 → 强校验 → 不可变快照 → 启动审批）
 *
 * @author Li
 */
public interface FillSubmissionService {

    /**
     * 提交：状态与 serverVersion 的工作项 CAS 冻结草稿（旧编辑事务此后必然 CAS 失败），
     * 服务端重读草稿执行强校验；通过则生成不可变快照并经 Outbox 可靠启动 BPM，
     * 失败则返回结构化问题清单并回到填报中/整改中
     */
    FillSubmitRespVO submit(Long workItemId, FillSubmitReqVO reqVO);

    /**
     * 撤回审批中的提交（仅实际提交人）：先经 BPM 成功终止流程实例，
     * 保留原快照，实例转入整改中重新开放草稿
     */
    void withdraw(Long submissionId, String reason);

    /**
     * 工作项的全部提交记录（按提交次数倒序）
     */
    List<FillSubmissionRespVO> getSubmissionsByWorkItem(Long workItemId);

    /**
     * 提交快照只读预览（审批人从 BPM 业务表单打开；含模板 schema 与全部快照行）
     */
    FillSubmissionPreviewRespVO getSubmissionPreview(Long submissionId);

}
