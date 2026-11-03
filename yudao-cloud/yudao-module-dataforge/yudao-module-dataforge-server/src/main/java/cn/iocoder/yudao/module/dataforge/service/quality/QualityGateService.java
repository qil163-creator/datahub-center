package cn.iocoder.yudao.module.dataforge.service.quality;

import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import cn.iocoder.yudao.module.governance.api.quality.dto.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 研发只负责质量门禁编排，规则执行和报告仍归属治理模块。 */
@Service
public class QualityGateService {

    @Resource
    private QualityTaskApi qualityTaskApi;

    public record Result(String status, Long instanceId, String message) {

    }

    /** 质量任务为选填；配置后，执行身份必须具备该质量任务的使用权限。 */
    public void validate(Long qualityTaskId, Long executionUserId) {
        if (qualityTaskId == null) {
            return;
        }
        if (executionUserId == null) {
            throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        }
        qualityTaskApi.validateForRun(qualityTaskId, executionUserId).checkError();
    }

    /**
     * 评估质量门禁。
     *
     * @param runKey 运行键
     * @param qualityTaskId 质量任务编号
     * @param executionUserId 执行用户编号
     * @return 结果
     */
    public Result evaluate(String runKey, Long qualityTaskId, Long executionUserId) {
        if (qualityTaskId == null) {
            return new Result("NOT_CONFIGURED", null, "未配置质量检查，不代表质量通过");
        }
        if (executionUserId == null || runKey == null || runKey.isBlank()) {
            return new Result("UNKNOWN", null, "缺少运行关联信息或执行身份");
        }
        Long instanceId = null;
        try {
            QualityRunReqDTO request = new QualityRunReqDTO();
            request.setQualityTaskId(qualityTaskId);
            request.setExecutionUserId(executionUserId);
            request.setRequestKey("dataforge:" + runKey);
            instanceId = qualityTaskApi.executeForRun(request).getCheckedData();
            QualityTaskResultRespDTO result = qualityTaskApi.getRunResult(instanceId, executionUserId).getCheckedData();
            return assess(instanceId, result);
        } catch (Exception failure) {
            // 远端可能已接受请求。下一次沿相同运行键查询/提交，不新建另一份质检。
            return new Result("UNKNOWN", instanceId, "质量服务暂不可确认，请等待对账");
        }
    }

    /**
     * 获取质量门禁的评估。
     *
     * @param instanceId 实例编号
     * @param result 处理结果
     * @return 结果
     */
    static Result assess(Long instanceId, QualityTaskResultRespDTO result) {
        if (result == null || instanceId == null || !instanceId.equals(result.getInstanceId())) {
            return new Result("UNKNOWN", instanceId, "未获取到本次质量结果");
        }
        String state = result.getInstanceStatus();
        if ("QUEUED".equals(state) || "RUNNING".equals(state)) {
            return new Result("PENDING", instanceId, "质量检查中");
        }
        if ("UNKNOWN".equals(state)) {
            return new Result("UNKNOWN", instanceId, "质量执行状态待确认");
        }
        boolean passed = "SUCCESS".equals(state)
                && result.getTotalCount() != null && result.getTotalCount() > 0
                && Long.valueOf(0).equals(result.getFailCount())
                && Long.valueOf(0).equals(result.getErrorRuleCount())
                && Long.valueOf(0).equals(result.getFailedRuleCount());
        return new Result(passed ? "PASSED" : "FAILED", instanceId,
                passed ? "质量检查通过" : "检查失败、空数据或存在不合格规则，成果不可发布");
    }
}
