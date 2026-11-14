package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillChildActionReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillDispatchReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAssigneeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillDispatchTemplateDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillRollupSourceDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillDispatchTemplateMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillRollupSourceMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillApprovalStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillAssigneeStateEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillTaskStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多级下发：上级审核、下发范围、汇总写入与上报前检查
 */
class FillHierarchyServiceTest {

    private final FillWorkItemMapper workItemMapper = mock(FillWorkItemMapper.class);
    private final FillAssigneeMapper assigneeMapper = mock(FillAssigneeMapper.class);
    private final FillTaskMapper taskMapper = mock(FillTaskMapper.class);
    private final FillSubmissionMapper submissionMapper = mock(FillSubmissionMapper.class);
    private final FillSubmissionRowMapper submissionRowMapper = mock(FillSubmissionRowMapper.class);
    private final FillDispatchTemplateMapper templateMapper = mock(FillDispatchTemplateMapper.class);
    private final FillRollupSourceMapper rollupSourceMapper = mock(FillRollupSourceMapper.class);
    private final FillWorkItemService workItemService = mock(FillWorkItemService.class);
    private final FillDraftService draftService = mock(FillDraftService.class);
    private final FillTableVersionService tableVersionService = mock(FillTableVersionService.class);
    private final FillNotifyService notifyService = mock(FillNotifyService.class);
    private final DeptApi deptApi = mock(DeptApi.class);
    private final AdminUserApi adminUserApi = mock(AdminUserApi.class);
    private final FillHierarchyService service = new FillHierarchyService();
    private MockedStatic<SecurityFrameworkUtils> security;

    private final FillWorkItemDO parent = FillWorkItemDO.builder().id(11L).taskId(9L).planId(1L).tableVersionId(5L)
            .reportingOrgId(100L).orgPath("/100").level(0).status(FillWorkItemStatusEnum.FILLING.getStatus())
            .deadlineTime(LocalDateTime.now().plusDays(5)).build();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "workItemMapper", workItemMapper);
        ReflectionTestUtils.setField(service, "assigneeMapper", assigneeMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "submissionMapper", submissionMapper);
        ReflectionTestUtils.setField(service, "submissionRowMapper", submissionRowMapper);
        ReflectionTestUtils.setField(service, "dispatchTemplateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "rollupSourceMapper", rollupSourceMapper);
        ReflectionTestUtils.setField(service, "workItemService", workItemService);
        ReflectionTestUtils.setField(service, "draftService", draftService);
        ReflectionTestUtils.setField(service, "tableVersionService", tableVersionService);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
        ReflectionTestUtils.setField(service, "collabService", mock(FillCollabService.class));
        ReflectionTestUtils.setField(service, "deptApi", deptApi);
        ReflectionTestUtils.setField(service, "adminUserApi", adminUserApi);
        ReflectionTestUtils.setField(service, "transactionManager", mock(PlatformTransactionManager.class));
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(8L);
        when(workItemService.getWorkItemValidated(11L)).thenReturn(parent);
        when(taskMapper.selectById(9L)).thenReturn(FillTaskDO.builder().id(9L).planId(1L)
                .status(FillTaskStatusEnum.RUNNING.getStatus()).build());
        when(tableVersionService.getTableVersion(5L)).thenReturn(FillTableVersionDO.builder().id(5L).build());
    }

    @AfterEach
    void tearDown() {
        security.close();
    }

    private void parentAssignee(Long userId) {
        when(assigneeMapper.selectByWorkItemIdAndUserId(11L, userId)).thenReturn(FillAssigneeDO.builder()
                .workItemId(11L).userId(userId).state(FillAssigneeStateEnum.VALID.getState()).build());
    }

    private FillWorkItemDO reviewingChild() {
        FillWorkItemDO child = FillWorkItemDO.builder().id(21L).taskId(9L).parentWorkItemId(11L)
                .reportingOrgId(201L).status(FillWorkItemStatusEnum.APPROVING.getStatus())
                .currentSubmissionId(210L).build();
        when(workItemService.getWorkItemValidated(21L)).thenReturn(child);
        when(submissionMapper.selectById(210L)).thenReturn(FillSubmissionDO.builder().id(210L).workItemId(21L)
                .reviewMode(FillSubmissionDO.REVIEW_PARENT)
                .approvalStatus(FillApprovalStatusEnum.APPROVING.getStatus()).build());
        return child;
    }

    private static FillChildActionReqVO action(long childId, Boolean approve, String reason) {
        FillChildActionReqVO reqVO = new FillChildActionReqVO();
        reqVO.setChildWorkItemId(childId);
        reqVO.setApprove(approve);
        reqVO.setReason(reason);
        return reqVO;
    }

    @Test
    void parentAssigneeApprovesChildIntoRolledUp() {
        parentAssignee(8L);
        reviewingChild();
        when(submissionMapper.markReviewed(eq(210L), eq(FillApprovalStatusEnum.APPROVED.getStatus()), eq(8L),
                any(), any())).thenReturn(1);
        when(workItemMapper.compareAndSetStatus(21L, FillWorkItemStatusEnum.APPROVING.getStatus(),
                FillWorkItemStatusEnum.ROLLED_UP.getStatus())).thenReturn(1);

        service.review(action(21L, true, null));

        verify(workItemMapper).compareAndSetStatus(21L, FillWorkItemStatusEnum.APPROVING.getStatus(),
                FillWorkItemStatusEnum.ROLLED_UP.getStatus());
        verify(notifyService).sendToUsers(eq(FillNotifyTypeEnum.APPROVED), any(), any(), any(), any());
    }

    @Test
    void onlyParentAssigneesCanReviewAndRejectNeedsReason() {
        reviewingChild();
        assertThatThrownBy(() -> service.review(action(21L, true, null))).isInstanceOf(ServiceException.class);
        parentAssignee(8L);
        assertThatThrownBy(() -> service.review(action(21L, false, " "))).isInstanceOf(ServiceException.class);
        verify(submissionMapper, never()).markReviewed(anyLong(), any(), any(), any(), any());
    }

    @Test
    void cannotHandleChildrenAfterParentHasReported() {
        parentAssignee(8L);
        reviewingChild();
        parent.setStatus(FillWorkItemStatusEnum.APPROVING.getStatus());

        assertThatThrownBy(() -> service.review(action(21L, true, null)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("本单位已上报");
    }

    private static DeptRespDTO dept(long id, long parentId, String name) {
        DeptRespDTO dept = new DeptRespDTO();
        dept.setId(id);
        dept.setParentId(parentId);
        dept.setName(name);
        dept.setStatus(0);
        return dept;
    }

    private static FillDispatchReqVO dispatch(long orgId, long userId, LocalDateTime deadline) {
        FillDispatchReqVO.Org org = new FillDispatchReqVO.Org();
        org.setReportingOrgId(orgId);
        org.setAssigneeUserIds(List.of(userId));
        org.setDeadlineTime(deadline);
        FillDispatchReqVO reqVO = new FillDispatchReqVO();
        reqVO.setOrgs(List.of(org));
        return reqVO;
    }

    @Test
    void dispatchStaysInsideOwnSubtreeAndDeadline() {
        when(deptApi.getChildDeptList(100L)).thenReturn(CommonResult.success(List.of(
                dept(201L, 100L, "一科"), dept(202L, 201L, "一科一组"))));
        when(workItemMapper.selectListByTaskId(9L)).thenReturn(List.of(parent));
        AdminUserRespDTO outsider = new AdminUserRespDTO();
        outsider.setId(7L);
        outsider.setStatus(0);
        outsider.setDeptId(999L);
        AdminUserRespDTO insider = new AdminUserRespDTO();
        insider.setId(6L);
        insider.setStatus(0);
        insider.setDeptId(202L);
        insider.setNickname("王五");
        when(adminUserApi.getUserList(any())).thenReturn(CommonResult.success(List.of(outsider, insider)));
        LocalDateTime ok = LocalDateTime.now().plusDays(2);

        // 不在本单位子树内的组织
        assertThatThrownBy(() -> service.dispatch(11L, dispatch(300L, 6L, ok))).isInstanceOf(ServiceException.class);
        // 截止时间晚于本单位
        assertThatThrownBy(() -> service.dispatch(11L, dispatch(201L, 6L, LocalDateTime.now().plusDays(9))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("不能晚于本单位截止时间");
        // 填报人不属于该下级组织子树
        assertThatThrownBy(() -> service.dispatch(11L, dispatch(201L, 7L, ok)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("一科");
        verify(workItemMapper, never()).insert(any(FillWorkItemDO.class));
    }

    @Test
    void generationAppliesDefaultListsRecursivelyWithoutRemoteCalls() {
        FillTaskDO task = FillTaskDO.builder().id(9L).planId(1L).openTime(LocalDateTime.now().minusDays(1)).build();
        when(workItemMapper.selectListByTaskId(9L)).thenReturn(List.of(parent));
        when(templateMapper.selectListByPlanAndParent(1L, 100L)).thenReturn(List.of(FillDispatchTemplateDO.builder()
                .planId(1L).parentOrgId(100L).reportingOrgId(201L).orgName("一科").orgPath("/100/201")
                .deadlineOffsetHours(24)
                .assigneeUserIds(JsonUtils.toJsonString(List.of(Map.of("userId", "6", "userName", "王五", "deptId", "201"))))
                .build()));
        when(templateMapper.selectListByPlanAndParent(1L, 201L)).thenReturn(List.of());

        service.applyDispatchTemplates(task, parent);

        ArgumentCaptor<FillWorkItemDO> created = ArgumentCaptor.forClass(FillWorkItemDO.class);
        verify(workItemMapper).insert(created.capture());
        assertThat(created.getValue().getParentWorkItemId()).isEqualTo(11L);
        assertThat(created.getValue().getLevel()).isEqualTo(1);
        assertThat(created.getValue().getDeadlineTime()).isEqualTo(parent.getDeadlineTime().minusHours(24));
        verify(assigneeMapper).insert(any(FillAssigneeDO.class));
        verify(deptApi, never()).getChildDeptList(anyLong());
        verify(adminUserApi, never()).getUserList(any());
    }

    @Test
    void rollupWritesRowsAndRecordsSourcesThenSubmitBecomesReady() {
        when(workItemMapper.selectListByParentId(11L)).thenReturn(List.of(
                FillWorkItemDO.builder().id(21L).parentWorkItemId(11L).reportingOrgId(201L)
                        .status(FillWorkItemStatusEnum.ROLLED_UP.getStatus()).currentSubmissionId(210L).build()));
        when(submissionMapper.selectById(210L)).thenReturn(FillSubmissionDO.builder().id(210L)
                .approvalStatus(FillApprovalStatusEnum.APPROVED.getStatus()).submissionType(1).build());
        when(submissionRowMapper.selectListBySubmissionId(210L)).thenReturn(List.of(FillSubmissionRowDO.builder()
                .rowId("r1").data("{\"amount\":\"3\"}").build()));
        when(draftService.replaceRollupRows(eq(parent), any(), eq(6L), eq(8L))).thenReturn(4);

        var resp = service.rollup(11L, 6L);

        assertThat(resp.getChildCount()).isEqualTo(1);
        assertThat(resp.getRollupRowCount()).isEqualTo(1);
        assertThat(resp.getDraftRowCount()).isEqualTo(4);
        verify(rollupSourceMapper).deleteByWorkItemId(11L);
        ArgumentCaptor<FillRollupSourceDO> source = ArgumentCaptor.forClass(FillRollupSourceDO.class);
        verify(rollupSourceMapper).insert(source.capture());
        assertThat(source.getValue().getChildSubmissionId()).isEqualTo(210L);

        // 汇总前：未记录来源 → 不能上报；记录后可以上报
        when(rollupSourceMapper.selectListByWorkItemId(11L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.checkReadyToSubmit(parent)).isInstanceOf(ServiceException.class);
        when(rollupSourceMapper.selectListByWorkItemId(11L)).thenReturn(List.of(source.getValue()));
        service.checkReadyToSubmit(parent);
    }

}
