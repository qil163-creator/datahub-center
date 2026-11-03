package cn.iocoder.yudao.module.governance.service.standardterm;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.vo.CategoryTermListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categoryterm.CategoryTermDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardterm.StandardTermDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categoryterm.CategoryTermMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardterm.StandardTermMapper;
import cn.iocoder.yudao.module.governance.service.categoryterm.CategoryTermServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StandardTermCategoryTest extends BaseMockitoUnitTest {
    @Mock private StandardTermMapper terms;
    @Mock private CategoryTermMapper categories;
    @Mock private CategoryDatametaMapper dataCategories;
    @Mock private StandardElementMapper elements;
    @InjectMocks private StandardTermServiceImpl service;
    @InjectMocks private CategoryTermServiceImpl categoryService;

    @Test
    void selectingParentIncludesAllDescendantsWithoutUnrelatedCategories() {
        when(categories.selectList(any(CategoryTermListReqVO.class))).thenReturn(List.of(
                category(1, 0, 0), category(2, 1, 1), category(3, 2, 0), category(4, 0, 0)));
        when(terms.selectTermPage(any(), anyList())).thenAnswer(invocation -> {
            assertEquals(Set.of(1L, 2L, 3L), Set.copyOf(invocation.getArgument(1)));
            return PageResult.empty();
        });
        var query = new StandardTermPageReqVO();
        query.setTermCategoryId(1L);
        service.getStandardTermPage(query);
        verify(terms).selectTermPage(eq(query), anyList());
        query.setTermCategoryId(null);
        service.getStandardTermPage(query);
        verify(terms).selectTermPage(query, null);
    }

    @Test
    void savesOnlyAfterLockingExistingCategoryIncludingDisabled() throws Exception {
        var request = new StandardTermSaveReqVO();
        request.setName("测试");
        request.setDefinition("定义");
        request.setStatus((short) 0);
        request.setTermCategoryId(2L);
        when(categories.selectByIdForUpdate(2L)).thenReturn(category(2, 1, 1));
        service.createStandardTerm(request);
        verify(categories).selectByIdForUpdate(2L);
        verify(terms).insert(argThat((StandardTermDO term) -> Long.valueOf(2).equals(term.getTermCategoryId())));
        assertNotNull(StandardTermServiceImpl.class.getMethod("createStandardTerm", StandardTermSaveReqVO.class)
                .getAnnotation(Transactional.class));
        assertNotNull(StandardTermServiceImpl.class.getMethod("updateStandardTerm", StandardTermSaveReqVO.class)
                .getAnnotation(Transactional.class));
        request.setTermCategoryId(99L);
        assertThrows(ServiceException.class, () -> service.createStandardTerm(request));
    }

    @Test
    void deleteRejectsReferencedCategoryWhileHoldingItsLock() {
        when(categories.selectByIdForUpdate(2L)).thenReturn(category(2, 1, 1));
        when(terms.selectCountByTermCategoryId(2L)).thenReturn(1L);
        assertThrows(ServiceException.class, () -> categoryService.deleteCategoryTerm(2L));
        verify(categories, never()).deleteById(2L);
        when(terms.selectCountByTermCategoryId(2L)).thenReturn(0L);
        categoryService.deleteCategoryTerm(2L);
        verify(categories).deleteById(2L);
    }

    @Test
    void omittedAndExplicitNullCategoryHaveDifferentWireSemantics() {
        var mapper = JsonMapper.builder().build();
        var omitted = mapper.readValue("{}", StandardTermSaveReqVO.class);
        var cleared = mapper.readValue("{\"termCategoryId\":null}", StandardTermSaveReqVO.class);
        var changed = mapper.readValue("{\"termCategoryId\":\"9007199254740993\"}", StandardTermSaveReqVO.class);
        assertFalse(omitted.isTermCategorySpecified());
        assertTrue(cleared.isTermCategorySpecified());
        assertNull(cleared.getTermCategoryId());
        assertEquals(9007199254740993L, changed.getTermCategoryId());
        assertTrue(changed.isTermCategorySpecified());
        assertFalse(mapper.writeValueAsString(changed).contains("termCategorySpecified"));
        changed.setName("反序列化术语");
        changed.setDefinition("定义");
        changed.setStatus((short) 0);
        when(categories.selectByIdForUpdate(9007199254740993L))
                .thenReturn(category(9007199254740993L, 0L, 0));
        service.createStandardTerm(changed);
        verify(terms).insert(argThat((StandardTermDO term) -> Long.valueOf(9007199254740993L).equals(term.getTermCategoryId())));
    }

    private static CategoryTermDO category(long id, long parentId, int status) {
        return CategoryTermDO.builder().id(id).parentId(parentId).status((short) status).build();
    }
}
