package cn.iocoder.yudao.module.governance.service.doc;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydatameta.CategoryDatametaDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.doc.StandardDocDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import cn.iocoder.yudao.module.governance.service.categorydatameta.CategoryDatametaServiceImpl;
import cn.iocoder.yudao.module.infra.api.file.FileApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StandardDocCatalogTest {
    private final StandardDocMapper documents = mock(StandardDocMapper.class);
    private final CategoryDatametaMapper categories = mock(CategoryDatametaMapper.class);
    private final StandardCatalogService catalog = mock(StandardCatalogService.class);
    private final FileApi files = mock(FileApi.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final StandardDocServiceImpl service = new StandardDocServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "docMapper", documents);
        ReflectionTestUtils.setField(service, "categoryMapper", categories);
        ReflectionTestUtils.setField(service, "catalogService", catalog);
        ReflectionTestUtils.setField(service, "fileApi", files);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(categories.selectByIdForUpdate(2L)).thenReturn(CategoryDatametaDO.builder().id(2L).build());
        when(files.getFileAccessUrl(90L, null)).thenReturn(CommonResult.success("https://files.example.test/registered.pdf"));
    }

    @Test
    void createRequiresCategoryAndRegisteredFile() {
        var request = request();
        request.setCategoryId(null);
        assertThatThrownBy(() -> service.registerDoc(request)).hasMessageContaining("标准类目");
        request.setCategoryId(2L);
        request.setFileId(null);
        assertThatThrownBy(() -> service.registerDoc(request)).hasMessageContaining("标准原文不存在");
        verifyNoInteractions(files, documents, transactions);
    }

    @Test
    void createUsesRegisteredUrlBeforeOpeningTransactionAndStoresCatalogFields() {
        var request = request();
        request.setId(999L);
        request.setFileUrl("https://untrusted.example.test/forged.pdf");
        request.setVersionNo("2026.1");
        request.setAbolishDate(LocalDate.of(2028, 1, 1));
        when(documents.insert(any(StandardDocDO.class))).thenAnswer(invocation -> {
            StandardDocDO row = invocation.getArgument(0);
            assertThat(row.getId()).isNull();
            assertThat(row.getCategoryId()).isEqualTo(2L);
            assertThat(row.getFileUrl()).isEqualTo("https://files.example.test/registered.pdf");
            assertThat(row.getVersionNo()).isEqualTo("2026.1");
            assertThat(row.getAbolishDate()).isEqualTo(LocalDate.of(2028, 1, 1));
            row.setId(100L);
            return 1;
        });

        assertThat(service.createDoc(request)).isEqualTo(100L);

        var order = inOrder(files, transactions, categories, documents);
        order.verify(files).getFileAccessUrl(90L, null);
        order.verify(transactions).getTransaction(any());
        order.verify(categories).selectByIdForUpdate(2L);
        verify(transactions).commit(any());
    }

    @Test
    void legacyUpdateCanRemoveAttachmentWhilePreservingAbsentNewFields() {
        var existing = StandardDocDO.builder().id(10L).stdType("NATIONAL")
                .fileName("历史文件.pdf").fileUrl("https://legacy.example.test/doc.pdf")
                .versionNo("2026-A").abolishDate(LocalDate.of(2028, 1, 1))
                .registryNo("STD202601010001").registryTime(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        when(documents.selectByIdForUpdate(10L)).thenReturn(existing);
        when(documents.selectById(10L)).thenReturn(existing);
        var request = request();
        request.setId(10L);
        request.setCategoryId(null);
        request.setFileId(null);
        request.setFileName(null);
        request.setFileUrl(null);

        service.updateDoc(request);

        verify(documents).updateById(argThat((StandardDocDO row) -> row.getCategoryId() == null
                && row.getFileId() == null && row.getFileName() == null && row.getFileUrl() == null
                && "2026-A".equals(row.getVersionNo())
                && existing.getAbolishDate().equals(row.getAbolishDate())
                && existing.getRegistryNo().equals(row.getRegistryNo())));
        verifyNoInteractions(files);
    }

    @Test
    void legacyCreateStillAllowsMissingRegistrationFields() {
        var request = request();
        request.setCategoryId(null);
        request.setFileId(null);
        request.setStdCode(null);
        service.createDoc(request);
        verify(documents).insert(argThat((StandardDocDO row) -> row.getCategoryId() == null
                && row.getFileId() == null && row.getStdCode() == null));
        verifyNoInteractions(files, categories);
    }

    @Test
    void registrationUpdateCanClearOptionalFieldsWithoutDeletingLegacyFile() {
        var existing = StandardDocDO.builder().id(10L).stdType("NATIONAL").categoryId(2L)
                .fileId(90L).versionNo("2026-A").abolishDate(LocalDate.of(2028, 1, 1)).build();
        when(documents.selectById(10L)).thenReturn(existing);
        when(documents.selectByIdForUpdate(10L)).thenReturn(existing);
        var request = request();
        request.setId(10L);
        service.updateRegisteredDoc(request);
        verify(documents).updateById(argThat((StandardDocDO row) -> row.getVersionNo() == null
                && row.getAbolishDate() == null && row.getFileId().equals(90L)));
    }

    @Test
    void rejectsInvalidDatesBeforeFileLookup() {
        var request = request();
        request.setPublishDate(LocalDate.of(2026, 3, 1));
        request.setImplementDate(LocalDate.of(2026, 2, 1));
        assertThatThrownBy(() -> service.registerDoc(request)).hasMessageContaining("实施日期");
        request.setImplementDate(LocalDate.of(2026, 4, 1));
        request.setAbolishDate(LocalDate.of(2026, 3, 1));
        assertThatThrownBy(() -> service.registerDoc(request)).hasMessageContaining("废止日期");
        verifyNoInteractions(files, documents);
    }

    @Test
    void documentCannotBeDeletedOrChangeTypeWhileReferencedByCodeSet() {
        var existing = StandardDocDO.builder().id(10L).stdType("LOCAL").build();
        when(documents.selectByIdForUpdate(10L)).thenReturn(existing);
        when(documents.selectById(10L)).thenReturn(existing);
        when(documents.selectCodeSetReferenceCount(10L)).thenReturn(1L);
        assertThatThrownBy(() -> service.deleteDoc(10L)).hasMessageContaining("引用");
        var request = request();
        request.setId(10L);
        request.setFileId(null);
        assertThatThrownBy(() -> service.updateDoc(request)).hasMessageContaining("变更标准类型");
        verify(documents, never()).deleteById(anyLong());
        verify(documents, never()).updateById(any(StandardDocDO.class));
    }

    @Test
    void categoryCannotBeDeletedWhileReferencedByDocument() {
        var categoryService = new CategoryDatametaServiceImpl();
        ReflectionTestUtils.setField(categoryService, "categoryDatametaMapper", categories);
        ReflectionTestUtils.setField(categoryService, "standardDocMapper", documents);
        when(documents.selectCount(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class), eq(2L))).thenReturn(1L);
        assertThatThrownBy(() -> categoryService.deleteCategoryDatameta(2L)).hasMessageContaining("已被标准文件引用");
        verify(categories, never()).deleteById(anyLong());
    }

    @Test
    void pageExpandsServerSideCategoryDescendants() {
        var query = new StandardDocPageReqVO();
        query.setCategoryId(2L);
        query.setCategoryIds(List.of(999L));
        when(catalog.descendants(2L)).thenReturn(List.of(2L, 3L));
        service.getDocPage(query);
        verify(documents).selectPage(argThat(page -> List.of(2L, 3L).equals(page.getCategoryIds())));
    }

    private StandardDocSaveReqVO request() {
        var request = new StandardDocSaveReqVO();
        request.setNameCn("测试标准");
        request.setStdCode("GB-2026");
        request.setStdType("NATIONAL");
        request.setCategoryId(2L);
        request.setImplementStatus("DRAFT");
        request.setFileId(90L);
        request.setFileName("标准.pdf");
        request.setStatus((short) 0);
        request.setSort(0);
        return request;
    }
}
