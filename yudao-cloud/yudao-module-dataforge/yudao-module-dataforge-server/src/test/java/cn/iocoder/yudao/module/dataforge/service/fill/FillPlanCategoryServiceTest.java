package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanCategoryDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FillPlanCategoryServiceTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FillPlanCategoryServiceImpl service;
    @Mock
    private FillPlanCategoryMapper categories;
    @Mock
    private FillPlanMapper plans;

    @BeforeEach
    void clearPlatformContext() {
        TenantContextHolder.clear();
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void creationTrimsNameDefaultsSortAndIgnoresClientId() {
        FillPlanCategorySaveReqVO request = request(99L, 0L, "  基础信息  ");
        doAnswer(call -> {
            call.<FillPlanCategoryDO>getArgument(0).setId(10L);
            return 1;
        }).when(categories).insert(any(FillPlanCategoryDO.class));

        assertEquals(10L, service.createCategory(request));

        ArgumentCaptor<FillPlanCategoryDO> saved = ArgumentCaptor.forClass(FillPlanCategoryDO.class);
        var order = inOrder(categories);
        order.verify(categories).lockCategoryTree();
        order.verify(categories).selectByParentIdAndName(0L, "基础信息");
        order.verify(categories).insert(saved.capture());
        assertEquals("基础信息", saved.getValue().getName());
        assertEquals(0, saved.getValue().getSort());
    }

    @Test
    void creationRejectsMissingParentAndSameParentDuplicate() {
        assertError(FILL_PLAN_CATEGORY_PARENT_NOT_EXISTS,
                () -> service.createCategory(request(null, 20L, "基础信息")));
        when(categories.selectByParentIdAndName(0L, "基础信息"))
                .thenReturn(category(1L, 0L));
        assertError(FILL_PLAN_CATEGORY_NAME_DUPLICATE,
                () -> service.createCategory(request(null, 0L, " 基础信息 ")));
        verify(categories, never()).insert(any(FillPlanCategoryDO.class));
    }

    @Test
    void movingCategoryUnderItsDescendantIsRejectedBeforeAnyWrite() {
        when(categories.selectById(10L)).thenReturn(category(10L, 0L));
        when(categories.selectById(12L)).thenReturn(category(12L, 11L));
        when(categories.selectById(11L)).thenReturn(category(11L, 10L));
        assertError(FILL_PLAN_CATEGORY_PARENT_INVALID,
                () -> service.updateCategory(request(10L, 12L, "基础信息")));
        verify(categories, never()).updateById(any(FillPlanCategoryDO.class));
    }

    @Test
    void malformedExistingParentCycleIsRejectedWithoutLooping() {
        when(categories.selectById(11L)).thenReturn(category(11L, 12L));
        when(categories.selectById(12L)).thenReturn(category(12L, 11L));
        assertError(FILL_PLAN_CATEGORY_PARENT_INVALID,
                () -> service.createCategory(request(null, 11L, "基础信息")));
    }

    @Test
    void categoriesWithChildrenOrPlanReferencesCannotBeDeleted() {
        when(categories.selectById(10L)).thenReturn(category(10L, 0L));
        when(categories.selectCountByParentId(10L)).thenReturn(1L);
        assertError(FILL_PLAN_CATEGORY_HAS_CHILDREN, () -> service.deleteCategory(10L));
        verifyNoInteractions(plans);

        when(categories.selectCountByParentId(10L)).thenReturn(0L);
        when(plans.selectCountByCategoryId(10L)).thenReturn(1L);
        assertError(FILL_PLAN_CATEGORY_HAS_PLANS, () -> service.deleteCategory(10L));
        verify(categories, never()).deleteById(10L);
    }

    @Test
    void planReferenceValidationRejectsUnavailableCategoryAndAllowsUnclassified() {
        service.validateCategoryReference(null);
        verifyNoInteractions(categories);
        assertError(FILL_PLAN_CATEGORY_NOT_EXISTS, () -> service.validateCategoryReference(20L));
        var order = inOrder(categories);
        order.verify(categories).lockCategoryTree();
        order.verify(categories).selectById(20L);
    }

    @Test
    void parentFilterIncludesOnlySelectedSubtreeAndUnknownCategoryFailsClosed() {
        when(categories.selectListOrdered()).thenReturn(List.of(category(10L, 0L), category(11L, 10L),
                category(12L, 11L), category(20L, 0L)));
        assertEquals(List.of(10L, 11L, 12L), service.getCategoryAndDescendantIds(10L));
        assertEquals(List.of(12L), service.getCategoryAndDescendantIds(12L));
        assertError(FILL_PLAN_CATEGORY_NOT_EXISTS, () -> service.getCategoryAndDescendantIds(99L));
    }

    private static FillPlanCategorySaveReqVO request(Long id, Long parentId, String name) {
        var request = new FillPlanCategorySaveReqVO();
        request.setId(id);
        request.setParentId(parentId);
        request.setName(name);
        return request;
    }

    private static FillPlanCategoryDO category(Long id, Long parentId) {
        return FillPlanCategoryDO.builder().id(id).parentId(parentId).name("分类" + id).sort(0).build();
    }

    private static void assertError(ErrorCode expected, Executable action) {
        assertEquals(expected.getCode(), assertThrows(ServiceException.class, action).getCode());
    }

}
