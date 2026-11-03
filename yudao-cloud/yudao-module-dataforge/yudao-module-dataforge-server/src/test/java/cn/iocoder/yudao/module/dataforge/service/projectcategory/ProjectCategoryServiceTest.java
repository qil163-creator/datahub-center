package cn.iocoder.yudao.module.dataforge.service.projectcategory;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.dir.DevDirSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.CategorySaveReq;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategoryStatusReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevDirDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationCategoryDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevDirMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.projectcategory.ProjectCategoryMapper;
import cn.iocoder.yudao.module.dataforge.service.dev.DevDirService;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationTaskService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectCategoryServiceTest extends BaseMockitoUnitTest {
    @InjectMocks private ProjectCategoryService service;
    @Mock private DevDirService devDirs;
    @Mock private DevDirMapper devMapper;
    @Mock private IntegrationTaskService integrations;
    @Mock private IntegrationCategoryMapper integrationMapper;
    @Mock private ProjectCategoryMapper snapshots;
    @Mock private AdminUserApi users;
    @Mock private SecurityFrameworkService security;

    @BeforeEach
    void login() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "project-category-dev"), DevDirDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "project-category-integration"), IntegrationCategoryDO.class);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousQueriesNeverReadEitherFactTable() {
        SecurityContextHolder.clearContext();
        assertThrows(ServiceException.class, () -> service.list("integration"));
        verifyNoInteractions(devDirs, devMapper, integrations, integrationMapper, snapshots, users);
    }

    @Test
    void unknownKindCannotSelectAnUnintendedDomain() {
        assertThrows(ServiceException.class, () -> service.list("other"));
        verifyNoInteractions(devDirs, devMapper, integrations, integrationMapper, snapshots, users);
    }

    @Test
    void developmentQueryUsesItsExactManagementPermission() {
        assertThrows(ServiceException.class, () -> service.list("development"));
        verify(security).hasPermission("dataforge:dev-category:query");
        verifyNoInteractions(devDirs, devMapper, integrations, integrationMapper, snapshots, users);
    }

    @Test
    void integrationCreationUsesItsExactManagementPermission() {
        assertThrows(ServiceException.class, () -> service.create(request("integration")));
        verify(security).hasPermission("dataforge:integration-category:create");
        verifyNoInteractions(devDirs, devMapper, integrations, integrationMapper, snapshots, users);
    }

    @Test
    void updateWithoutIdentityFailsBeforeEitherOwningServiceOrMapper() {
        when(security.hasPermission("dataforge:dev-category:update")).thenReturn(true);

        assertThrows(ServiceException.class, () -> service.update(request("development")));

        verifyNoInteractions(devDirs, devMapper, integrations, integrationMapper, snapshots, users);
    }

    @Test
    void developmentCreationDelegatesDomainValidationAndPreservesLargeParentIdentity() {
        when(security.hasPermission("dataforge:dev-category:create")).thenReturn(true);
        when(devDirs.createDir(any(DevDirSaveReqVO.class))).thenReturn(9007199254740995L);
        var request = request("development");
        request.setParentId(9007199254740993L);
        request.setStatus(1);

        assertEquals(9007199254740995L, service.create(request));

        var capture = ArgumentCaptor.forClass(DevDirSaveReqVO.class);
        verify(devDirs).createDir(capture.capture());
        assertEquals(9007199254740993L, capture.getValue().getParentId());
        assertEquals("测试类目", capture.getValue().getName());
        assertEquals("类目描述", capture.getValue().getDescription());
        assertEquals(1, capture.getValue().getStatus());
        assertEquals(3, capture.getValue().getSort());
        verifyNoInteractions(integrations, integrationMapper, snapshots, users);
    }

    @Test
    void integrationCreationMapsUnifiedDisabledStatusToExistingValidFlag() {
        when(security.hasPermission("dataforge:integration-category:create")).thenReturn(true);
        when(integrations.saveCategory(any(CategorySaveReq.class), org.mockito.ArgumentMatchers.eq(false))).thenReturn(81L);
        var request = request("integration");
        request.setStatus(1);

        assertEquals(81L, service.create(request));

        var capture = ArgumentCaptor.forClass(CategorySaveReq.class);
        verify(integrations).saveCategory(capture.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertFalse(capture.getValue().getValidFlag());
        assertEquals("测试类目", capture.getValue().getName());
        assertEquals("类目描述", capture.getValue().getDescription());
        assertEquals(3, capture.getValue().getSort());
        verifyNoInteractions(devDirs, devMapper, snapshots, users);
    }

    @Test
    void referenceRejectionFromOwningDomainIsNotConvertedIntoDeletionSuccess() {
        when(security.hasPermission("dataforge:integration-category:delete")).thenReturn(true);
        when(integrationMapper.selectById(81L)).thenReturn(integrationCategory(81L, 0L));
        var rejection = new ServiceException(400, "类目仍被任务引用");
        org.mockito.Mockito.doThrow(rejection).when(integrations).deleteCategory(81L);

        assertEquals(rejection, assertThrows(ServiceException.class, () -> service.delete("integration", 81L)));

        verify(integrations).deleteCategory(81L);
        verifyNoInteractions(devDirs, devMapper, snapshots, users);
    }

    @Test
    void emptyListDoesNotRequireSnapshotOrUserRpc() {
        when(security.hasPermission("dataforge:dev-category:query")).thenReturn(true);
        when(devMapper.selectListAll()).thenReturn(List.of());

        assertTrue(service.list("development").isEmpty());

        verifyNoInteractions(snapshots, users, integrations, integrationMapper);
    }

    @Test
    void integrationListReadsCurrentFactsAndSeparatesImportedNamesFromLocalActors() {
        when(security.hasPermission("dataforge:integration-category:query")).thenReturn(true);
        var imported = integrationCategory(9007199254740993L, 0L);
        imported.setName("源系统类目");
        imported.setDescription("源描述");
        imported.setValidFlag(true);
        imported.setCreator("migration:0216");
        imported.setCreateTime(LocalDateTime.of(2026, 7, 29, 17, 51));
        var local = integrationCategory(3L, 9007199254740993L);
        local.setName("当前类目");
        local.setValidFlag(false);
        local.setCreator("7");
        when(integrationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(imported, local));
        when(snapshots.selectIntegrationCreatorNames(List.of(imported.getId(), local.getId())))
                .thenReturn(List.of(new ProjectCategoryMapper.SourceCreator(imported.getId(), "源创建人")));
        var actor = new AdminUserRespDTO();
        actor.setId(7L);
        actor.setNickname("本地创建人");
        when(users.getUserList(List.of(7L))).thenReturn(CommonResult.success(List.of(actor)));

        var rows = service.list("integration");

        assertEquals(9007199254740993L, rows.getFirst().getId());
        assertEquals("源创建人", rows.getFirst().getCreatorName());
        assertEquals(0, rows.getFirst().getStatus());
        assertEquals(imported.getCreateTime(), rows.getFirst().getCreateTime());
        assertEquals("源描述", rows.getFirst().getDescription());
        assertEquals(9007199254740993L, rows.get(1).getParentId());
        assertEquals(1, rows.get(1).getStatus());
        assertEquals("本地创建人", rows.get(1).getCreatorName());
        verify(users).getUserList(List.of(7L));
        verifyNoInteractions(devDirs, devMapper);
    }

    @Test
    void detailReloadsDisabledDevelopmentFactAndPrefersItsSourceCreatorName() {
        when(security.hasPermission("dataforge:dev-category:query")).thenReturn(true);
        var directory = DevDirDO.builder().id(9007199254740993L).parentId(0L)
                .name("禁用类目").description("当前数据库描述").status(1).sort(4).build();
        directory.setCreator("7");
        when(devMapper.selectById(directory.getId())).thenReturn(directory);
        when(snapshots.selectDevelopmentCreatorNames(List.of(directory.getId())))
                .thenReturn(List.of(new ProjectCategoryMapper.SourceCreator(directory.getId(), "源创建人")));

        var detail = service.get("development", directory.getId());

        assertEquals(directory.getId(), detail.getId());
        assertEquals(1, detail.getStatus());
        assertEquals("当前数据库描述", detail.getDescription());
        assertEquals("源创建人", detail.getCreatorName());
        verify(devMapper).selectById(9007199254740993L);
        verifyNoInteractions(users, devDirs, integrations, integrationMapper);
    }

    @Test
    void developmentCountsComeFromCurrentTaskAndDirectChildAggregates() {
        when(security.hasPermission("dataforge:dev-category:query")).thenReturn(true);
        when(devMapper.selectListAll()).thenReturn(List.of(
                DevDirDO.builder().id(1L).parentId(0L).status(0).build(),
                DevDirDO.builder().id(2L).parentId(1L).status(0).build()));
        when(snapshots.selectDevelopmentTaskCounts(List.of(1L, 2L)))
                .thenReturn(List.of(new ProjectCategoryMapper.TaskCount(1L, 3L)));
        when(snapshots.selectDevelopmentChildCounts(List.of(1L, 2L)))
                .thenReturn(List.of(new ProjectCategoryMapper.ChildCount(1L, 1L)));

        var rows = service.list("development");

        assertEquals(3L, rows.getFirst().getTaskCount());
        assertEquals(1L, rows.getFirst().getChildCount());
        assertEquals(0L, rows.get(1).getTaskCount());
        assertEquals(0L, rows.get(1).getChildCount());
        verifyNoInteractions(users, integrations, integrationMapper);
    }

    @Test
    void integrationCountsUseItsOwnTaskAndChildFacts() {
        when(security.hasPermission("dataforge:integration-category:query")).thenReturn(true);
        when(integrationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                integrationCategory(5L, 0L), integrationCategory(6L, 5L)));
        when(snapshots.selectIntegrationTaskCounts(List.of(5L, 6L)))
                .thenReturn(List.of(new ProjectCategoryMapper.TaskCount(5L, 9L)));
        when(snapshots.selectIntegrationChildCounts(List.of(5L, 6L)))
                .thenReturn(List.of(new ProjectCategoryMapper.ChildCount(5L, 1L)));

        var rows = service.list("integration");

        assertEquals(9L, rows.getFirst().getTaskCount());
        assertEquals(1L, rows.getFirst().getChildCount());
        assertEquals(0L, rows.get(1).getTaskCount());
        assertEquals(0L, rows.get(1).getChildCount());
        verifyNoInteractions(users, devDirs, devMapper);
    }

    @Test
    void enabledCategoriesCannotBypassSingleOrBatchDeleteStateRules() {
        when(security.hasPermission("dataforge:integration-category:delete")).thenReturn(true);
        var enabled = integrationCategory(81L, 0L);
        enabled.setValidFlag(true);
        when(integrationMapper.selectById(81L)).thenReturn(enabled);
        when(integrationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(integrationCategory(80L, 0L), enabled));

        assertThrows(ServiceException.class, () -> service.delete("integration", 81L));
        assertThrows(ServiceException.class, () -> service.deleteList("integration", List.of(80L, 81L)));

        verifyNoInteractions(integrations, devDirs, devMapper, snapshots, users);
    }

    @Test
    void developmentStatusUsesOwningServiceWithoutSendingACachedDefinition() {
        when(security.hasPermission("dataforge:dev-category:update")).thenReturn(true);
        var request = status("development", 9007199254740993L, 1);

        service.updateStatus(request);

        verify(devDirs).updateDirStatus(9007199254740993L, 1);
        verify(devDirs, never()).updateDir(any(DevDirSaveReqVO.class));
        verifyNoInteractions(devMapper, integrations, integrationMapper, snapshots, users);
    }

    @Test
    void integrationStatusUpdatesOnlyItsPersistedFlag() {
        when(security.hasPermission("dataforge:integration-category:update")).thenReturn(true);
        var current = integrationCategory(81L, 0L);
        current.setName("当前名称");
        current.setDescription("当前描述");
        when(integrationMapper.selectById(81L)).thenReturn(current);
        when(integrationMapper.updateById(any(IntegrationCategoryDO.class))).thenReturn(1);

        service.updateStatus(status("integration", 81L, 1));

        var capture = ArgumentCaptor.forClass(IntegrationCategoryDO.class);
        verify(integrationMapper).updateById(capture.capture());
        assertEquals(81L, capture.getValue().getId());
        assertFalse(capture.getValue().getValidFlag());
        assertNull(capture.getValue().getName());
        assertNull(capture.getValue().getDescription());
        assertNull(capture.getValue().getParentId());
        assertNull(capture.getValue().getSort());
        verify(integrationMapper).lockTree();
        verifyNoInteractions(devDirs, devMapper, integrations, snapshots, users);
    }

    @Test
    void selectedParentAndDescendantsDeleteLeavesFirstAndDeduplicateIds() {
        when(security.hasPermission("dataforge:integration-category:delete")).thenReturn(true);
        when(integrationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                integrationCategory(1L, 0L), integrationCategory(2L, 1L), integrationCategory(3L, 2L)));

        service.deleteList("integration", List.of(1L, 2L, 3L, 2L));

        var order = inOrder(integrations);
        order.verify(integrations).deleteCategory(3L);
        order.verify(integrations).deleteCategory(2L);
        order.verify(integrations).deleteCategory(1L);
        order.verifyNoMoreInteractions();
        verify(integrationMapper).lockTree();
        verifyNoInteractions(devDirs, devMapper, snapshots, users);
    }

    @Test
    void unselectedDescendantRejectsTheWholeBatchBeforeAnyDeletion() {
        when(security.hasPermission("dataforge:integration-category:delete")).thenReturn(true);
        when(integrationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                integrationCategory(1L, 0L), integrationCategory(2L, 1L), integrationCategory(3L, 2L)));

        assertThrows(ServiceException.class, () -> service.deleteList("integration", List.of(1L, 2L)));

        verifyNoInteractions(integrations, devDirs, devMapper, snapshots, users);
    }

    @Test
    void unknownSelectedIdRejectsTheWholeBatchBeforeAnyDeletion() {
        when(security.hasPermission("dataforge:dev-category:delete")).thenReturn(true);
        when(devMapper.selectListAll()).thenReturn(List.of(DevDirDO.builder().id(1L).parentId(0L).status(1).build()));

        assertThrows(ServiceException.class, () -> service.deleteList("development", List.of(1L, 2L)));

        verify(devMapper).lockTree();
        verifyNoInteractions(devDirs, integrations, integrationMapper, snapshots, users);
    }

    private ProjectCategoryStatusReqVO status(String kind, Long id, Integer value) {
        var request = new ProjectCategoryStatusReqVO();
        request.setKind(kind);
        request.setId(id);
        request.setStatus(value);
        return request;
    }

    private IntegrationCategoryDO integrationCategory(Long id, Long parentId) {
        var category = new IntegrationCategoryDO();
        category.setId(id);
        category.setParentId(parentId);
        category.setSort(0);
        category.setValidFlag(false);
        return category;
    }

    private ProjectCategorySaveReqVO request(String kind) {
        var request = new ProjectCategorySaveReqVO();
        request.setKind(kind);
        request.setParentId(0L);
        request.setName("测试类目");
        request.setDescription("类目描述");
        request.setStatus(0);
        request.setSort(3);
        return request;
    }
}
