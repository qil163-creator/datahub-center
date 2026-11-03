package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_RAW_FORBIDDEN;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_UNASSIGNED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QualityWorkOrderGuardTest {

    @Mock private PermissionApi permissionApi;
    @Mock private AdminUserApi adminUserApi;
    @InjectMocks private QualityWorkOrderGuard guard;

    @Test
    void t24OtherDeptCannotSeeAssignedWorkOrder() {
        QualityWorkOrderDO order = QualityWorkOrderDO.builder()
                .id(8L).ownerUserId(2L).creatorUserId(3L).confirmerUserId(5L)
                .handlingDeptId(20L).responsibilityStatus(QualityWorkOrderDO.RESP_ASSIGNED).build();
        stubUser(9L, 99L);
        deny(9L, QualityWorkOrderGuard.PERM_ASSIGN);
        deny(9L, QualityWorkOrderGuard.PERM_COORDINATE);
        assertThatThrownBy(() -> guard.assertVisible(order, 9L))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
    }

    @Test
    void t24UnassignedQueueRequiresAssignPermission() {
        QualityWorkOrderDO order = QualityWorkOrderDO.builder()
                .id(8L).responsibilityStatus(QualityWorkOrderDO.RESP_UNASSIGNED).build();
        deny(9L, QualityWorkOrderGuard.PERM_ASSIGN);
        assertThatThrownBy(() -> guard.assertVisible(order, 9L))
                .extracting("code").isEqualTo(QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
    }

    @Test
    void t24UnassignedCannotBeHandledEvenWithAssignPermission() {
        QualityWorkOrderDO order = QualityWorkOrderDO.builder()
                .id(8L).responsibilityStatus(QualityWorkOrderDO.RESP_UNASSIGNED).build();
        when(permissionApi.hasAnyPermissions(9L, QualityWorkOrderGuard.PERM_ASSIGN))
                .thenReturn(CommonResult.success(true));
        assertThatThrownBy(() -> guard.assertHandle(order, 9L))
                .extracting("code").isEqualTo(QUALITY_WORK_ORDER_UNASSIGNED.getCode());
    }

    @Test
    void t23RawPermissionIsIndependentOfVisibility() {
        QualityWorkOrderDO order = QualityWorkOrderDO.builder()
                .id(8L).ownerUserId(9L).responsibilityStatus(QualityWorkOrderDO.RESP_ASSIGNED).build();
        deny(9L, QualityWorkOrderGuard.PERM_RAW);
        assertThatThrownBy(() -> guard.assertRaw(order, 9L))
                .extracting("code").isEqualTo(QUALITY_WORK_ORDER_RAW_FORBIDDEN.getCode());
    }

    @Test
    void t24ConfirmPermissionIsRequired() {
        QualityWorkOrderDO order = QualityWorkOrderDO.builder()
                .id(8L).ownerUserId(9L).responsibilityStatus(QualityWorkOrderDO.RESP_ASSIGNED).build();
        deny(9L, QualityWorkOrderGuard.PERM_CONFIRM);
        assertThatThrownBy(() -> guard.assertConfirm(order, 9L))
                .extracting("code").isEqualTo(QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
    }

    private void stubUser(Long userId, Long deptId) {
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(userId);
        user.setDeptId(deptId);
        when(adminUserApi.getUser(userId)).thenReturn(CommonResult.success(user));
    }

    private void deny(Long userId, String permission) {
        when(permissionApi.hasAnyPermissions(userId, permission)).thenReturn(CommonResult.success(false));
    }

    @Test
    void missingDepartmentsDoNotGrantVisibility() {
        QualityWorkOrderDO order = QualityWorkOrderDO.builder().id(8L).ownerUserId(2L)
                .responsibilityStatus(QualityWorkOrderDO.RESP_ASSIGNED).build();
        deny(9L, QualityWorkOrderGuard.PERM_ASSIGN);
        deny(9L, QualityWorkOrderGuard.PERM_COORDINATE);
        assertThatThrownBy(() -> guard.assertVisible(order, 9L))
                .extracting("code").isEqualTo(QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
    }
}
