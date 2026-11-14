package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线填报-上期数据：同一计划紧邻上一期、同一报送组织最新一次审批通过的提交快照。
 * 供“上期数据带入”与跨期校验规则共用。
 *
 * @author Li
 */
@Component
public class FillPreviousPeriodService {

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillSubmissionMapper submissionMapper;

    @Resource
    private FillSubmissionRowMapper submissionRowMapper;

    /**
     * 上期任务中该组织最新一次审批通过的提交（含零上报）；没有时返回 null。
     *
     * @param previousTask 上期任务
     * @param reportingOrgId 报送组织
     * @return 提交快照头
     */
    public FillSubmissionDO findApprovedSubmission(FillTaskDO previousTask, Long reportingOrgId) {
        if (previousTask == null || reportingOrgId == null) {
            return null;
        }
        FillWorkItemDO previous = workItemMapper.selectByTaskIdAndOrgId(previousTask.getId(), reportingOrgId);
        if (previous == null || previous.getCurrentSubmissionId() == null) {
            return null;
        }
        FillSubmissionDO submission = submissionMapper.selectById(previous.getCurrentSubmissionId());
        return submission != null && FillApprovalStatusEnum.APPROVED.getStatus().equals(submission.getApprovalStatus())
                ? submission : null;
    }

    /**
     * 工作项对应的上期数据行（fieldCode → 值）；没有上期任务或上期未审批通过时返回 null，零上报返回空列表。
     *
     * @param item 当前工作项
     * @return 上期数据行
     */
    public List<Map<String, Object>> findApprovedRows(FillWorkItemDO item) {
        FillTaskDO task = taskMapper.selectById(item.getTaskId());
        if (task == null || task.getPeriodStart() == null) {
            return null;
        }
        FillTaskDO previousTask = taskMapper.selectPreviousTask(task.getPlanId(), task.getPeriodStart(), task.getId());
        FillSubmissionDO submission = findApprovedSubmission(previousTask, item.getReportingOrgId());
        if (submission == null) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FillSubmissionRowDO row : submissionRowMapper.selectListBySubmissionId(submission.getId())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = row.getData() == null ? null : JsonUtils.parseObject(row.getData(), Map.class);
            rows.add(data == null ? new HashMap<>() : data);
        }
        return rows;
    }

}
