package cn.iocoder.yudao.module.governance.service.standardelement;

import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.enums.standard.StandardElementLifecycleEnum;
import cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompilationService;
import cn.iocoder.yudao.module.governance.service.quality.StandardQualityRuleMaterializer;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StandardElementMutationTest {

    private StandardElementMapper elementMapper;
    private StandardConstraintCompilationService constraintCompilationService;
    private StandardQualityRuleMaterializer materializer;
    private StandardElementServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        elementMapper = mock(StandardElementMapper.class);
        constraintCompilationService = mock(StandardConstraintCompilationService.class);
        materializer = mock(StandardQualityRuleMaterializer.class);
        service = new StandardElementServiceImpl();
        ReflectionTestUtils.setField(service, "catalogService", mock(StandardCatalogService.class));
        ReflectionTestUtils.setField(service, "elementRuleMapper", mock(cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementRuleMapper.class));
        ReflectionTestUtils.setField(service, "standardElementMapper", elementMapper);
        ReflectionTestUtils.setField(service, "standardConstraintCompilationService", constraintCompilationService);
        ReflectionTestUtils.setField(service, "standardQualityRuleMaterializer", materializer);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void ordinaryUpdateShouldAllowUnchangedLegacyDirtyNames() {
        StandardElementDO existing = element(1, " 历史名称 ", "Bad Name",
                StandardElementLifecycleEnum.DRAFT.getStatus());
        when(elementMapper.selectByIdForUpdate(1)).thenReturn(existing);
        StandardElementSaveReqVO request = request(1, " 历史名称 ", "Bad Name");
        request.setCode("CLIENT-SHOULD-NOT-CHANGE-CODE");
        request.setDescription("ordinary property update");

        service.updateStandardElement(request);

        verify(elementMapper, never()).selectByName(anyString());
        verify(elementMapper, never()).selectByEngName(anyString());
        ArgumentCaptor<StandardElementDO> update = ArgumentCaptor.forClass(StandardElementDO.class);
        verify(elementMapper).updateById(update.capture());
        assertThat(update.getValue().getCode()).isEqualTo("DE000001");
        assertThat(update.getValue().getLifecycleStatus()).isEqualTo(StandardElementLifecycleEnum.DRAFT.getStatus());
        // 普通属性更新保留原有名称。
        assertThat(update.getValue().getName()).isEqualTo(" 历史名称 ");
        assertThat(update.getValue().getEngName()).isEqualTo("Bad Name");
        assertThat(update.getValue().getDescription()).isEqualTo("ordinary property update");
    }

    @Test
    void renamedValueShouldBePersisted() {
        StandardElementDO existing = element(1, "Cafe\u0301", " customer_name ",
                StandardElementLifecycleEnum.DRAFT.getStatus());
        when(elementMapper.selectByIdForUpdate(1)).thenReturn(existing);
        StandardElementSaveReqVO request = request(1, "Caf\u00e9", "customer_name");

        service.updateStandardElement(request);

        ArgumentCaptor<StandardElementDO> update = ArgumentCaptor.forClass(StandardElementDO.class);
        verify(elementMapper).updateById(update.capture());
        assertThat(update.getValue().getName()).isEqualTo("Caf\u00e9");
        assertThat(update.getValue().getEngName()).isEqualTo("customer_name");
    }

    @Test
    void editableElementShouldAllowUniqueEnglishNameChange() {
        StandardElementDO existing = element(1, "客户名称", "Bad Name",
                StandardElementLifecycleEnum.DRAFT.getStatus());
        when(elementMapper.selectByIdForUpdate(1)).thenReturn(existing);
        when(elementMapper.selectByEngName("customer_name")).thenReturn(null);
        StandardElementSaveReqVO request = request(1, "客户名称", "customer_name");

        service.updateStandardElement(request);

        verify(elementMapper).selectByEngName("customer_name");
        ArgumentCaptor<StandardElementDO> update = ArgumentCaptor.forClass(StandardElementDO.class);
        verify(elementMapper).updateById(update.capture());
        assertThat(update.getValue().getEngName()).isEqualTo("customer_name");
        assertThat(update.getValue().getCode()).isEqualTo("DE000001");
    }

    @Test
    void compileFailureShouldHappenBeforeAnyLifecycleWrite() {
        StandardElementDO draft = element(1, "客户名称", "customer_name",
                StandardElementLifecycleEnum.DRAFT.getStatus());
        when(elementMapper.selectByIdForUpdate(1)).thenReturn(draft);
        doThrow(new IllegalStateException("EMPTY_CODE_SET")).when(constraintCompilationService)
                .requireCompilable(draft);

        assertThatThrownBy(() -> service.publishStandardElement(1)).hasMessageContaining("EMPTY_CODE_SET");

        verify(constraintCompilationService).requireCompilable(draft);
        verify(elementMapper, never()).updateById(any(StandardElementDO.class));
        verifyNoInteractions(materializer);
    }

    @Test
    void publishShouldCompileCurrentDefinitionAndMaterializeRules() {
        StandardElementDO draft = element(1, "客户名称", "customer_name", StandardElementLifecycleEnum.DRAFT.getStatus());
        when(elementMapper.selectByIdForUpdate(1)).thenReturn(draft);
        var compilation = mock(cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompiler.Compilation.class);
        when(constraintCompilationService.requireCompilable(draft)).thenReturn(compilation);

        service.publishStandardElement(1);

        assertThat(draft.getLifecycleStatus()).isEqualTo(StandardElementLifecycleEnum.PUBLISHED.getStatus());
        verify(elementMapper).updateById(any(StandardElementDO.class));
        verify(materializer).materialize(draft, compilation);
    }

    private static StandardElementDO element(Integer id, String name, String engName, String lifecycle) {
        return StandardElementDO.builder().id(id).code("DE000001").name(name).engName(engName)
                .lifecycleStatus(lifecycle).sort(1).status((short) 0).build();
    }

    private static StandardElementSaveReqVO request(Integer id, String name, String engName) {
        StandardElementSaveReqVO request = new StandardElementSaveReqVO();
        request.setRevision(0L);
        request.setId(id);
        request.setName(name);
        request.setEngName(engName);
        request.setSort(1);
        request.setStatus((short) 0);
        return request;
    }

}
