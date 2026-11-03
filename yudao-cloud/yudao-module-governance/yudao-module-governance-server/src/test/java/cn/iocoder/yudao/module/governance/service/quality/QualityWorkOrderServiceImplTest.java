package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderEtlReportDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.etl.InlineQualityReportDO;
import cn.iocoder.yudao.module.governance.dal.mysql.etl.InlineQualityReportMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderEtlReportMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class QualityWorkOrderServiceImplTest {

    @Mock
    private QualityWorkOrderMapper workOrderMapper;
    @Mock
    private InlineQualityReportMapper inlineQualityReportMapper;
    @Mock
    private QualityWorkOrderEtlReportMapper workOrderEtlReportMapper;
    @Mock
    private QualityWorkOrderGuard guard;
    @Mock
    private PermissionApi permissionApi;
    @Mock
    private AdminUserApi adminUserApi;
    @InjectMocks
    private QualityWorkOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "work-order-etl"),
                QualityWorkOrderDO.class);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void instanceSuccessIsNotVerifiedPass() {
        QualityTaskInstanceDO instance = QualityTaskInstanceDO.builder()
                .id(99L).instanceStatus(QualityTaskInstanceDO.STATUS_SUCCESS).build();
        QualityWorkOrderDO order = QualityWorkOrderDO.builder()
                .id(8L).orderStatus(QualityWorkOrderDO.STATUS_RECHECKING).build();
        assertThat(instance.getInstanceStatus()).isEqualTo(QualityTaskInstanceDO.STATUS_SUCCESS);
        assertThat(order.getCloseType()).isNotEqualTo(QualityWorkOrderDO.CLOSE_VERIFIED_PASS);
        assertThat(order.getOrderStatus()).isNotEqualTo(QualityWorkOrderDO.STATUS_PROCESSED);
    }

    @Test
    void createInlineEtlWorkOrderPersistsReportCountsWithoutSqlIssues() {
        when(inlineQualityReportMapper.selectByIdForUpdate(22L)).thenReturn(InlineQualityReportDO.builder()
                .id(22L).sourceType("ETL_INLINE").totalCount(10L).passCount(5L).warnCount(2L).rejectCount(3L)
                .build());
        when(workOrderEtlReportMapper.selectByReportId(22L)).thenReturn(null);
        when(guard.deptId(7L)).thenReturn(4L);
        doAnswer(invocation -> {
            ((QualityWorkOrderDO) invocation.getArgument(0)).setId(99L);
            return 1;
        }).when(workOrderMapper).insert(any(QualityWorkOrderDO.class));
        when(workOrderMapper.update(eq(null), any())).thenReturn(1);
        when(workOrderEtlReportMapper.insert(any(QualityWorkOrderEtlReportDO.class))).thenReturn(1);

        Long id = service.createInlineEtlWorkOrder(7L, 22L, "ETL 检查", "描述", null);

        assertThat(id).isEqualTo(99L);
        org.mockito.ArgumentCaptor<QualityWorkOrderDO> orderCaptor =
                org.mockito.ArgumentCaptor.forClass(QualityWorkOrderDO.class);
        verify(workOrderMapper).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getSourceType()).isEqualTo("ETL_INLINE");
        assertThat(orderCaptor.getValue().getTaskId()).isNull();
        assertThat(orderCaptor.getValue().getIssueCount()).isEqualTo(5L);
        assertThat(orderCaptor.getValue().getOwnerUserId()).isEqualTo(7L);
        assertThat(orderCaptor.getValue().getHandlingDeptId()).isEqualTo(4L);
        assertThat(orderCaptor.getValue().getResponsibilityStatus()).isEqualTo(QualityWorkOrderDO.RESP_ASSIGNED);
        verify(workOrderEtlReportMapper).insert(any(QualityWorkOrderEtlReportDO.class));
    }

    @Test
    void lowPrivilegeCreatorCanQueryCreatedInlineOrderAndRecoverLink() {
        when(inlineQualityReportMapper.selectByIdForUpdate(22L)).thenReturn(InlineQualityReportDO.builder()
                .id(22L).sourceType("ETL_INLINE").totalCount(10L).passCount(5L).warnCount(2L).rejectCount(3L)
                .build());
        QualityWorkOrderEtlReportDO link = QualityWorkOrderEtlReportDO.builder()
                .reportId(22L).workOrderId(99L).build();
        when(workOrderEtlReportMapper.selectByReportId(22L)).thenReturn(null, link);
        when(adminUserApi.getUser(7L)).thenReturn(CommonResult.success(user(7L, 4L)));
        QualityWorkOrderGuard lowPrivilegeGuard = new QualityWorkOrderGuard();
        ReflectionTestUtils.setField(lowPrivilegeGuard, "permissionApi", permissionApi);
        ReflectionTestUtils.setField(lowPrivilegeGuard, "adminUserApi", adminUserApi);
        ReflectionTestUtils.setField(service, "guard", lowPrivilegeGuard);
        when(workOrderMapper.update(eq(null), any())).thenReturn(1);
        when(workOrderEtlReportMapper.insert(any(QualityWorkOrderEtlReportDO.class))).thenReturn(1);
        QualityWorkOrderDO[] persisted = new QualityWorkOrderDO[1];
        doAnswer(invocation -> {
            persisted[0] = invocation.getArgument(0);
            persisted[0].setId(99L);
            return 1;
        }).when(workOrderMapper).insert(any(QualityWorkOrderDO.class));
        when(workOrderMapper.selectById(99L)).thenAnswer(invocation -> persisted[0]);

        service.createInlineEtlWorkOrder(7L, 22L, "ETL 检查", "描述", null);
        Long visibleId = service.getVisibleInlineReportWorkOrderId(22L, 7L);

        assertThat(visibleId).isEqualTo(99L);
        assertThat(persisted[0].getOwnerUserId()).isEqualTo(7L);
        assertThat(persisted[0].getResponsibilityStatus()).isEqualTo(QualityWorkOrderDO.RESP_ASSIGNED);
        verify(permissionApi, never()).hasAnyPermissions(7L, QualityWorkOrderGuard.PERM_ASSIGN);
    }

    private AdminUserRespDTO user(Long id, Long deptId) {
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(id);
        user.setDeptId(deptId);
        return user;
    }

    @Test
    void createInlineEtlWorkOrderRejectsAlreadyLinkedReportWithoutDuplicateTicket() {
        when(inlineQualityReportMapper.selectByIdForUpdate(22L)).thenReturn(InlineQualityReportDO.builder()
                .id(22L).sourceType("ETL_INLINE").warnCount(1L).rejectCount(0L).build());
        when(workOrderEtlReportMapper.selectByReportId(22L)).thenReturn(QualityWorkOrderEtlReportDO.builder()
                .reportId(22L).workOrderId(99L).build());
        assertThatThrownBy(() -> service.createInlineEtlWorkOrder(7L, 22L, "ETL 检查", null, null))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants
                        .QUALITY_WORK_ORDER_ETL_REPORT_ALREADY_LINKED.getCode());
        verify(guard).deptId(7L);
        verify(workOrderMapper, never()).insert(any(QualityWorkOrderDO.class));
    }

    @Test
    void linkedInlineReportReadChecksExistingWorkOrderVisibility() {
        var order = QualityWorkOrderDO.builder().id(99L).sourceType("ETL_INLINE").build();
        when(workOrderEtlReportMapper.selectByReportId(22L)).thenReturn(QualityWorkOrderEtlReportDO.builder()
                .reportId(22L).workOrderId(99L).build());
        when(workOrderMapper.selectById(99L)).thenReturn(order);

        assertThat(service.getVisibleInlineReportWorkOrderId(22L, 7L)).isEqualTo(99L);

        verify(guard).assertVisible(order, 7L);
    }

}
