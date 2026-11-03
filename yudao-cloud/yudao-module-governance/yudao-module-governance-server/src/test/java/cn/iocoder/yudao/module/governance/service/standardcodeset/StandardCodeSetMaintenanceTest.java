package cn.iocoder.yudao.module.governance.service.standardcodeset;

import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.dal.dataobject.doc.StandardDocDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydatameta.CategoryDatametaDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class StandardCodeSetMaintenanceTest {
    private final StandardCodeSetMapper codeSets = mock(StandardCodeSetMapper.class);
    private final StandardCodeItemMapper items = mock(StandardCodeItemMapper.class);
    private final StandardElementMapper elements = mock(StandardElementMapper.class);
    private final StandardCodeSetServiceImpl service = new StandardCodeSetServiceImpl();
    private final StandardDocMapper documents = mock(StandardDocMapper.class);
    private final CategoryDatametaMapper categories = mock(CategoryDatametaMapper.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "catalogService", mock(cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService.class));
        ReflectionTestUtils.setField(service, "valueMappings", mock(cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeMappingMapper.class));
        ReflectionTestUtils.setField(service, "standardCodeSetMapper", codeSets);
        ReflectionTestUtils.setField(service, "standardCodeItemMapper", items);
        ReflectionTestUtils.setField(service, "standardElementMapper", elements);
        ReflectionTestUtils.setField(service, "standardDocMapper", documents);
        ReflectionTestUtils.setField(service, "categoryMapper", categories);
        when(codeSets.selectByIdForUpdate(7))
                .thenReturn(StandardCodeSetDO.builder().id(7).type("STATUS").build());
    }

    @Test
    void createRejectsDocumentDeletedAfterCatalogLookup() {
        var request = new StandardCodeSetSaveReqVO();
        request.setCategoryId(2L);
        request.setDocumentId(10L);
        request.setStandardType("NATIONAL");
        when(categories.selectByIdForUpdate(2L)).thenReturn(CategoryDatametaDO.builder().id(2L).build());

        assertThatThrownBy(() -> service.createStandardCodeSet(request)).hasMessageContaining("标准文件不存在");
        verify(codeSets, never()).insert(any(StandardCodeSetDO.class));
    }

    @Test
    void createLocksSharedCategoryBeforeDocumentAndPersistsOwnerLink() {
        var request = new StandardCodeSetSaveReqVO();
        request.setCategoryId(2L);
        request.setDocumentId(10L);
        request.setStandardType("NATIONAL");
        when(categories.selectByIdForUpdate(2L)).thenReturn(CategoryDatametaDO.builder().id(2L).build());
        when(documents.selectByIdForUpdate(10L)).thenReturn(StandardDocDO.builder().id(10L).stdType("NATIONAL").build());

        service.createStandardCodeSet(request);

        var order = inOrder(categories, documents, codeSets);
        order.verify(categories).selectByIdForUpdate(2L);
        order.verify(documents).selectByIdForUpdate(10L);
        order.verify(codeSets).insert(argThat((StandardCodeSetDO row) -> row.getDocumentId().equals(10L)));
    }

    @Test
    void deleteStillRejectsCodeSetReferencedByStandardElement() {
        when(elements.selectCountByCodeSetIds(List.of(7))).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteStandardCodeSet(7)).hasMessageContaining("引用");

        verifyNoInteractions(items);
        verify(codeSets, never()).deleteById(7);
    }

    @Test
    void deleteUnreferencedCodeSetRemovesItemsBeforeParent() {
        when(elements.selectCountByCodeSetIds(List.of(7))).thenReturn(0L);

        service.deleteStandardCodeSet(7);

        var order = inOrder(codeSets, elements, items);
        order.verify(codeSets).selectByIdForUpdate(7);
        order.verify(elements).selectCountByCodeSetIds(List.of(7));
        order.verify(items).deleteByDictTypes(List.of("STATUS"));
        order.verify(codeSets).deleteById(7);
    }

    @Test
    void updatePreservesExistingBusinessType() {
        var request = new StandardCodeSetSaveReqVO();
        request.setRevision(0L);
        request.setId(7);
        request.setName("状态码");
        request.setType("CHANGED");
        request.setStatus((short) 1);

        service.updateStandardCodeSet(request);

        verify(codeSets).updateById(argThat((StandardCodeSetDO update) ->
                "STATUS".equals(update.getType()) && Short.valueOf((short) 1).equals(update.getStatus())));
        verifyNoInteractions(elements, items);
    }
}
