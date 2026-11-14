package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAttachmentDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillAttachmentMapper;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控件：级联、人员/部门编号、附件的校验与附件输入安全处理
 */
class FillControlServiceTest {

    private final DeptApi deptApi = mock(DeptApi.class);
    private final AdminUserApi adminUserApi = mock(AdminUserApi.class);
    private final FillAttachmentMapper attachmentMapper = mock(FillAttachmentMapper.class);
    private final FillControlService service = new FillControlService();
    private final FillWorkItemDO item = FillWorkItemDO.builder().id(21L).reportingOrgId(100L).build();

    FillControlServiceTest() {
        ReflectionTestUtils.setField(service, "deptApi", deptApi);
        ReflectionTestUtils.setField(service, "adminUserApi", adminUserApi);
        ReflectionTestUtils.setField(service, "attachmentMapper", attachmentMapper);
    }

    private static Map<String, Object> field(Object... pairs) {
        Map<String, Object> field = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            field.put((String) pairs[i], pairs[i + 1]);
        }
        return field;
    }

    private static DeptRespDTO dept(long id, String name) {
        DeptRespDTO dept = new DeptRespDTO();
        dept.setId(id);
        dept.setName(name);
        dept.setStatus(0);
        return dept;
    }

    @Test
    void personScopeDefaultsToReportingOrgAndItsChildren() {
        when(deptApi.getDept(100L)).thenReturn(CommonResult.success(dept(100L, "本单位")));
        when(deptApi.getChildDeptList(100L)).thenReturn(CommonResult.success(List.of(dept(101L, "下属科室"))));
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(7L);
        user.setNickname("张三");
        user.setStatus(0);
        when(adminUserApi.getUserListByDeptIds(List.of(100L, 101L))).thenReturn(CommonResult.success(List.of(user)));
        List<Map<String, Object>> fields = List.of(
                field("fieldCode", "owner", "name", "负责人", "refKind", "USER"),
                field("fieldCode", "unit", "name", "单位", "refKind", "DEPT"));

        Map<String, Set<String>> scopes = service.preloadPersonScopes(item, fields);
        var issues = service.validate(item, fields, List.of(
                Map.of("owner", "7", "unit", "101"),
                Map.of("owner", "8", "unit", "999")), scopes);

        assertThat(scopes).containsEntry("USER:ORG", Set.of("7")).containsEntry("DEPT:ORG", Set.of("100", "101"));
        assertThat(issues).extracting(FillControlService.Issue::ruleCode).containsExactly("USER_SCOPE", "DEPT_SCOPE");
        assertThat(issues).allMatch(issue -> issue.rowIndex() == 1);
        assertThat(service.resolveOptions(item, fields, List.of()).get("owner"))
                .containsExactly(new FillControlService.Option("7", "张三"));
    }

    @Test
    void cascadeValueMustBelongToTheParentValue() {
        List<Map<String, Object>> fields = List.of(field("fieldCode", "city", "name", "城市",
                "cascadeParent", "province", "cascadeOptions", Map.of("浙江", List.of("杭州", "宁波"))));

        var issues = service.validate(item, fields, List.of(
                Map.of("province", "浙江", "city", "杭州"),
                Map.of("province", "浙江", "city", "南京"),
                Map.of("province", "江苏", "city", "南京"),
                Map.of("city", "南京")), Map.of());

        assertThat(issues).extracting(FillControlService.Issue::rowIndex).containsExactly(1, 2);
        assertThat(issues).allMatch(issue -> issue.ruleCode().equals("CASCADE"));
    }

    @Test
    void attachmentsMustBeReadyAndBelongToThisWorkItemAndField() {
        FillAttachmentDO own = FillAttachmentDO.builder().id(31L).workItemId(21L).fieldCode("photo")
                .contentType("image/png").state(FillAttachmentDO.STATE_READY).build();
        FillAttachmentDO otherItem = FillAttachmentDO.builder().id(32L).workItemId(99L).fieldCode("photo")
                .contentType("image/png").state(FillAttachmentDO.STATE_READY).build();
        FillAttachmentDO notImage = FillAttachmentDO.builder().id(33L).workItemId(21L).fieldCode("photo")
                .contentType("application/pdf").state(FillAttachmentDO.STATE_READY).build();
        when(attachmentMapper.selectByIds(any())).thenReturn(List.of(own, otherItem, notImage));
        List<Map<String, Object>> fields = List.of(field("fieldCode", "photo", "name", "照片", "refKind", "IMAGE"));

        var issues = service.validate(item, fields, List.of(
                Map.of("photo", "31"), Map.of("photo", "31,32"), Map.of("photo", "33"),
                Map.of("photo", "31,abc"), Map.of("photo", "404")), Map.of());

        assertThat(issues).extracting(FillControlService.Issue::rowIndex).containsExactly(1, 2, 3, 4);

        service.bindAttachments(fields, List.of(Map.of("photo", "31")), 77L);
        verify(attachmentMapper).bindSubmission(Set.of(31L), 77L);
    }

    @Test
    void readOnlyLabelsResolveOnlyReferencedIdsIncludingDisabledPeople() {
        AdminUserRespDTO left = new AdminUserRespDTO();
        left.setId(7L);
        left.setNickname("张三");
        left.setStatus(1);
        when(adminUserApi.getUserList(Set.of(7L, 8L))).thenReturn(CommonResult.success(List.of(left)));
        when(deptApi.getDeptList(Set.of(101L))).thenReturn(CommonResult.success(List.of(dept(101L, "下属科室"))));
        when(attachmentMapper.selectByIds(any())).thenReturn(List.of(
                FillAttachmentDO.builder().id(31L).workItemId(21L).name("现场.jpg")
                        .state(FillAttachmentDO.STATE_READY).build(),
                FillAttachmentDO.builder().id(32L).workItemId(99L).name("别人的.jpg")
                        .state(FillAttachmentDO.STATE_READY).build(),
                FillAttachmentDO.builder().id(33L).workItemId(21L).name("已清理.jpg")
                        .state(FillAttachmentDO.STATE_DELETED).build()));
        List<Map<String, Object>> fields = List.of(
                field("fieldCode", "owner", "refKind", "USER"),
                field("fieldCode", "unit", "refKind", "DEPT"),
                field("fieldCode", "photo", "refKind", "IMAGE"),
                field("fieldCode", "memo"));

        var labels = service.resolveLabels(item, fields, List.of(
                Map.of("owner", "7,8", "unit", "101", "photo", "31,32,33", "memo", "x")));

        assertThat(labels).containsOnlyKeys("owner", "unit", "photo");
        assertThat(labels.get("owner")).containsExactly(new FillControlService.Option("7", "张三"));
        assertThat(labels.get("unit")).containsExactly(new FillControlService.Option("101", "下属科室"));
        assertThat(labels.get("photo")).containsExactly(new FillControlService.Option("31", "现场.jpg"));
        // 不按范围拉取整批人员
        verify(adminUserApi, never()).getUserListByDeptIds(any());
    }

    @Test
    void uploadedNamesAndImageTypesAreSanitized() {
        assertThat(FillAttachmentService.sanitizeName("..\\..\\etc/现场\u0007照片.jpg")).isEqualTo("现场照片.jpg");
        assertThatThrownBy(() -> FillAttachmentService.sanitizeName("report.html"))
                .isInstanceOf(ServiceException.class);
        assertThat(FillAttachmentService.imageType(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}))
                .isEqualTo("image/png");
        assertThat(FillAttachmentService.imageType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .isEqualTo("image/jpeg");
        assertThat(FillAttachmentService.imageType("<svg onload=alert(1)>".getBytes())).isNull();
    }

}
