package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务汇总：只合计审批通过的数据，数值为精确十进制字符串，未报口径包含结束时取消的组织
 */
class FillTaskSummaryServiceTest {

    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillWorkItemMapper workItemMapper = mock(FillWorkItemMapper.class);
    private final FillSubmissionMapper submissionMapper = mock(FillSubmissionMapper.class);
    private final FillSubmissionRowMapper submissionRowMapper = mock(FillSubmissionRowMapper.class);
    private final FillTableVersionService tableVersionService = mock(FillTableVersionService.class);
    private final FillTaskAccessGuard accessGuard = mock(FillTaskAccessGuard.class);
    private final FillTaskSummaryService service = new FillTaskSummaryService();

    private final FillTaskDO task = FillTaskDO.builder().id(3L).planId(1L).name("月报").periodKey("2026-10")
            .tableVersionId(5L).build();

    FillTaskSummaryServiceTest() {
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "submissionMapper", submissionMapper);
        ReflectionTestUtils.setField(service, "submissionRowMapper", submissionRowMapper);
        ReflectionTestUtils.setField(service, "tableVersionService", tableVersionService);
        ReflectionTestUtils.setField(service, "accessGuard", accessGuard);
        when(taskMapper.selectById(3L)).thenReturn(task);
        when(tableVersionService.getTableVersion(5L)).thenReturn(FillTableVersionDO.builder().id(5L)
                .fieldSchema(JsonUtils.toJsonString(List.of(
                        Map.of("fieldCode", "name", "fieldType", 2),
                        Map.of("fieldCode", "amount", "name", "金额", "fieldType", 1),
                        Map.of("fieldCode", "qty", "name", "数量", "fieldType", 5),
                        Map.of("fieldCode", "owner", "fieldType", 6, "refKind", "USER"),
                        Map.of("fieldCode", "secret", "fieldType", 1, "hidden", true)))).build());
    }

    private static FillWorkItemDO item(long id, String org, FillWorkItemStatusEnum status, Long submissionId) {
        return FillWorkItemDO.builder().id(id).taskId(3L).orgName(org).orgPath("/" + id)
                .status(status.getStatus()).overdue(0).currentSubmissionId(submissionId).build();
    }

    private static FillSubmissionRowDO row(long submissionId, Map<String, Object> data) {
        return FillSubmissionRowDO.builder().submissionId(submissionId).data(JsonUtils.toJsonString(data)).build();
    }

    @Test
    void totalsOnlyApprovedSubmissionsWithExactDecimals() {
        when(workItemMapper.selectListByTaskId(3L)).thenReturn(List.of(
                item(11L, "甲", FillWorkItemStatusEnum.MERGED, 101L),
                item(12L, "乙", FillWorkItemStatusEnum.APPROVING, 102L),
                item(13L, "丙", FillWorkItemStatusEnum.FILLING, null),
                item(14L, "丁", FillWorkItemStatusEnum.CANCELLED, null)));
        when(submissionMapper.selectByIds(any())).thenReturn(List.of(
                FillSubmissionDO.builder().id(101L).workItemId(11L)
                        .approvalStatus(FillApprovalStatusEnum.APPROVED.getStatus()).build(),
                FillSubmissionDO.builder().id(102L).workItemId(12L)
                        .approvalStatus(FillApprovalStatusEnum.APPROVING.getStatus()).build()));
        when(submissionRowMapper.selectListBySubmissionId(101L)).thenReturn(List.of(
                row(101L, Map.of("amount", "0.1", "qty", 2, "owner", "7")),
                row(101L, Map.of("amount", "0.2", "qty", "", "secret", "5"))));

        var summary = service.getSummary(3L);

        verify(accessGuard).checkInitiator(task);
        assertThat(summary.getFields()).extracting(f -> f.getFieldCode()).containsExactly("amount", "qty");
        assertThat(summary.getTotals()).containsExactly(Map.entry("amount", "0.3"), Map.entry("qty", "2"));
        assertThat(summary.getApprovedCount()).isEqualTo(1);
        assertThat(summary.getUnreportedCount()).isEqualTo(2);
        assertThat(summary.getOrgs()).extracting(o -> o.getApproved()).containsExactly(true, false, false, false);
        assertThat(summary.getOrgs().get(0).getRowCount()).isEqualTo(2);
        assertThat(summary.getOrgs().get(1).getTotals()).isEmpty();
    }

    @Test
    void nonInitiatorCannotSeeSummary() {
        doThrow(new IllegalStateException("denied")).when(accessGuard).checkInitiator(task);

        assertThatThrownBy(() -> service.getSummary(3L)).hasMessage("denied");
    }

}
