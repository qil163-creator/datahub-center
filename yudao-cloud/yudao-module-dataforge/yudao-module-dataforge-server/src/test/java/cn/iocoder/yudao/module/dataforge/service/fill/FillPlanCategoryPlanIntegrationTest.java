package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanOrgAssigneeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanOrgMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FillPlanCategoryPlanIntegrationTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FillPlanServiceImpl service;
    @Mock
    private FillPlanMapper plans;
    @Mock
    private FillPlanOrgMapper orgs;
    @Mock
    private FillPlanOrgAssigneeMapper assignees;
    @Mock
    private FillTableVersionService versions;
    @Mock
    private FillPlanCategoryService categories;

    @Test
    void enabledPlanCanMoveToCategoryAndThenClearCategory() {
        FillPlanDO old = FillPlanDO.builder().id(1L).status(1).tableVersionId(2L).periodType(1).build();
        when(plans.selectById(1L)).thenReturn(old);
        FillPlanSaveReqVO request = new FillPlanSaveReqVO();
        request.setId(1L);
        request.setTableVersionId(2L);
        request.setPeriodType(1);
        request.setCategoryId(10L);

        service.updatePlan(request);
        verify(categories).validateCategoryReference(10L);
        verify(plans).updateCategoryId(1L, 10L);

        request.setCategoryId(null);
        service.updatePlan(request);
        verify(categories).validateCategoryReference(null);
        verify(plans).updateCategoryId(1L, null);
    }

    @Test
    void categoryFilterUsesServerDescendantsAndEnrichesCategoryName() {
        FillPlanPageReqVO request = new FillPlanPageReqVO();
        request.setCategoryId(10L);
        when(categories.getCategoryAndDescendantIds(10L)).thenReturn(List.of(10L, 11L));
        when(plans.selectPage(request, List.of(10L, 11L)))
                .thenReturn(new PageResult<>(List.of(FillPlanDO.builder().id(1L).categoryId(11L).build()), 1L));
        when(categories.getCategoryNameMap(Set.of(11L))).thenReturn(Map.of(11L, "基础信息"));

        var page = service.getPlanPage(request);

        assertEquals(1L, page.getTotal());
        assertEquals(11L, page.getList().getFirst().getCategoryId());
        assertEquals("基础信息", page.getList().getFirst().getCategoryName());
    }

    @Test
    void unclassifiedFilterKeepsZeroAndDoesNotExpandARealCategory() {
        FillPlanPageReqVO request = new FillPlanPageReqVO();
        request.setCategoryId(0L);
        when(plans.selectPage(request, (List<Long>) null))
                .thenReturn(new PageResult<>(List.of(FillPlanDO.builder().id(1L).build()), 1L));
        when(categories.getCategoryNameMap(Set.of())).thenReturn(Map.of());

        var page = service.getPlanPage(request);
        assertEquals(1L, page.getTotal());
        assertNull(page.getList().getFirst().getCategoryName());

        verify(categories, never()).getCategoryAndDescendantIds(anyLong());
        verify(plans).selectPage(request, (List<Long>) null);
    }

}
