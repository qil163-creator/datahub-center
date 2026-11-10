package cn.iocoder.yudao.module.governance.service.standardelement;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.*;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.*;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.*;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.service.qualityrule.AuditRuleDimensionService;
import cn.iocoder.yudao.module.governance.service.standardcodeset.StandardCodeMappingService;
import cn.iocoder.yudao.module.modeling.api.model.ModelRuleContextApi;
import cn.iocoder.yudao.module.modeling.api.model.dto.ModelStandardFieldRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StandardElementWorkbenchTest {
    private final StandardElementRuleMapper rules = mock(StandardElementRuleMapper.class);
    private final StandardElementMapper elements = mock(StandardElementMapper.class);
    private final AuditRuleMapper audits = mock(AuditRuleMapper.class);
    private final CleanRuleMapper cleans = mock(CleanRuleMapper.class);
    private final CategoryCleanRuleMapper cleanCategories = mock(CategoryCleanRuleMapper.class);
    private final StandardCatalogService catalog = mock(StandardCatalogService.class);
    private final ModelRuleContextApi fields = mock(ModelRuleContextApi.class);
    private final SecurityFrameworkService security = mock(SecurityFrameworkService.class);
    private final StandardCodeItemMapper items = mock(StandardCodeItemMapper.class);
    private final StandardCodeSetMapper codeSets = mock(StandardCodeSetMapper.class);
    private StandardElementRuleService service;

    @BeforeEach void setup() {
        var runtime = mock(DbSwitchRuntime.class); when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
        service = new StandardElementRuleService(rules, elements, audits, cleans, cleanCategories,
                mock(AuditRuleDimensionService.class), new AuditSqlRegistry(), new CleanFunctionRegistry(), runtime, catalog, fields, security, items, codeSets);
        var element = StandardElementDO.builder().id(1).engName("amount").lifecycleStatus("DRAFT").build();
        when(elements.selectById(1)).thenReturn(element); when(elements.selectByIdForUpdate(1)).thenReturn(element);
        when(rules.selectByOwner(1)).thenReturn(List.of());
        when(audits.selectList()).thenReturn(List.of(audit(107, "CHARACTER_VALIDATION"), audit(115,"TIME_ORDER_VALIDATION"), audit(311,"COMPOSITE_UNIQUENESS_VALIDATION"), audit(112,"FOREIGN_KEY_VALIDATION"), audit(111,"ENUM_VALIDATION"), audit(306,"NUMERIC_RANGE_VALIDATION"), audit(999,"UNKNOWN")));
        when(cleans.selectList()).thenReturn(List.of(CleanRuleDO.builder().id(19L).code("019").name("空字段删除").strategyKey("REMOVE_EMPTY_COMBINATION").status(0).build(),
                CleanRuleDO.builder().id(29L).code("029").name("去重").strategyKey("KEEP_LATEST_OR_FIRST").status(0).build(),
                CleanRuleDO.builder().id(39L).code("039").name("清理过期记录").strategyKey("CHECK_EXPIRATION").status(0).build()));
    }

    @Test void catalogKeepsDisabledCardsAndDoesNotChangeTemplatesWhenSavingInstance() {
        var templates = service.templates("AUDIT");
        assertEquals(7, templates.size()); assertFalse(templates.stream().filter(t -> t.getCode().equals("999")).findFirst().orElseThrow().isEnabled());
        var request = request(107, Map.of("allowedTypes", List.of("digit"), "useRegex", false));
        service.create(request);
        var saved = ArgumentCaptor.forClass(StandardElementRuleDO.class); verify(rules).insert(saved.capture());
        assertTrue(saved.getValue().getConfigJson().contains("allowedTypes"));
        verify(audits, never()).updateById(any(AuditRuleDO.class)); verify(cleans, never()).updateById(any(CleanRuleDO.class));
        request.setTemplateId(999L); assertThrows(ServiceException.class, () -> service.create(request));
    }

    @ParameterizedTest
    @MethodSource("auditExamples")
    void implementedAuditTemplatesCanBeSaved(AuditExample example) {
        when(audits.selectList()).thenReturn(List.of(audit(example.code(), example.strategy())));
        when(security.hasPermission("modeling:model:query")).thenReturn(true);
        when(fields.getRuleContextFieldList(1L)).thenReturn(CommonResult.success(List.of(
                field(11, "amount"), field(11, "limit_amount"), field(11, "status"), field(11, "name"),
                field(12, "id"), field(12, "name"))));
        var template = service.templates("AUDIT").getFirst();
        assertTrue(template.isEngineSupported()); assertTrue(template.isEnabled()); assertNull(template.getDisabledReason());
        service.create(request(example.code(), example.config()));
        var saved = ArgumentCaptor.forClass(StandardElementRuleDO.class); verify(rules).insert(saved.capture());
        when(rules.selectByOwner(1)).thenReturn(List.of(saved.getValue()));
        assertEquals(example.strategy(), template.getStrategyKey());
        assertFalse(saved.getValue().getConfigJson().contains("reference_value"));
        verify(audits, never()).updateById(any(AuditRuleDO.class));
    }

    @Test void auditFieldBindingsRejectUnauthorizedModelsAndForgedMetadata() {
        when(audits.selectList()).thenReturn(List.of(audit(116, "NUMERIC_RELATION_VALIDATION"), audit(113, "MODEL_STRUCTURE_VALIDATION"),
                audit(403, "CROSS_TABLE_CONSISTENCY"), audit(404, "FOREIGN_KEY_VALIDATION"), audit(311, "COMPOSITE_UNIQUENESS_VALIDATION")));
        var relation = request(116, Map.of("conditions", List.of(Map.of("leftField", "amount", "rightField", "limit_amount", "operator", "<="))));
        assertThrows(ServiceException.class, () -> service.create(relation)); verifyNoInteractions(fields);
        when(security.hasPermission("modeling:model:query")).thenReturn(true);
        when(fields.getRuleContextFieldList(1L)).thenReturn(CommonResult.success(List.of(field(11,"amount"), field(12,"limit_amount"))));
        assertThrows(ServiceException.class, () -> service.create(relation));
        assertThrows(ServiceException.class, () -> service.create(request(311, Map.of("columnNames", List.of("amount")))));
        when(fields.getRuleContextFieldList(1L)).thenReturn(CommonResult.success(List.of(field(11,"amount"), field(11,"name"), field(12,"id"))));
        var cross = request(403, Map.of("objectKey", "11", "referenceObjectKey", "12", "columnNames", List.of("amount", "name"),
                "leftColumn", "amount", "refColumn", "id", "comparisons", List.of(Map.of("leftColumn", "name", "rightColumn", "secret"))));
        assertThrows(ServiceException.class, () -> service.create(cross));
        assertThrows(ServiceException.class, () -> service.create(request(404, Map.of("objectKey", "11", "referenceObjectKey", "12",
                "columnNames", List.of("amount"), "refColumn", "id", "comparisons", List.of(1)))));
        assertThrows(ServiceException.class, () -> service.create(request(113, Map.of("expectedColumns", List.of(Map.of("name", "amount")),
                "columnSnapshots", List.of(Map.of("name", "amount"))))));
        assertThrows(ServiceException.class, () -> service.create(request(113, Map.of("expectedColumns", List.of(Map.of("name", "amount")),
                "columnNames", "invalid"))));
        var disabled = audit(113, "MODEL_STRUCTURE_VALIDATION"); disabled.setStatus(1); when(audits.selectList()).thenReturn(List.of(disabled));
        assertFalse(service.templates("AUDIT").getFirst().isEnabled());
        assertThrows(ServiceException.class, () -> service.create(request(113, Map.of("expectedColumns", List.of(Map.of("name", "amount"))))));
        verify(rules, never()).insert(any(StandardElementRuleDO.class));
    }

    private record AuditExample(long code, String strategy, Map<String, Object> config) { }
    private static Stream<AuditExample> auditExamples() {
        var pair = Map.<String, Object>of("objectKey", "11", "columnNames", List.of("amount", "limit_amount"));
        var conditions = Map.<String, Object>of("objectKey", "11", "conditions", List.of(Map.of("leftField", "amount", "rightField", "limit_amount", "operator", "<=")));
        var conditional = Map.<String, Object>of("objectKey", "11", "conditionColumn", "status", "conditionValues", List.of("ACTIVE"), "requiredColumns", List.of("name"));
        var foreign = Map.<String, Object>of("objectKey", "11", "columnNames", List.of("amount"), "referenceObjectKey", "12", "refColumn", "id");
        return Stream.of(
                new AuditExample(107, "CHARACTER_VALIDATION", Map.of("allowedTypes", List.of("digit"))),
                new AuditExample(108, "LENGTH_VALIDATION", Map.of("minLength", 0)),
                new AuditExample(109, "DATE_FORMAT_VALIDATION", Map.of()),
                new AuditExample(110, "DECIMAL_PRECISION_VALIDATION", Map.of("scale", 0)),
                new AuditExample(111, "ENUM_VALIDATION", Map.of("validValues", List.of("A"))),
                new AuditExample(112, "FOREIGN_KEY_VALIDATION", foreign),
                new AuditExample(113, "MODEL_STRUCTURE_VALIDATION", Map.of("expectedColumns", List.of(Map.of("name", "amount")))),
                new AuditExample(114, "COLUMN_METADATA_COMPLETENESS", Map.of()),
                new AuditExample(115, "TIME_ORDER_VALIDATION", conditions),
                new AuditExample(116, "NUMERIC_RELATION_VALIDATION", conditions),
                new AuditExample(117, "CONDITIONAL_REQUIRED_VALIDATION", conditional),
                new AuditExample(118, "MUTUAL_EXCLUSION_VALIDATION", pair),
                new AuditExample(119, "SENSITIVE_MASK_VALIDATION", Map.of()),
                new AuditExample(203, "NOT_NULL_VALIDATION", Map.of()),
                new AuditExample(204, "PLACEHOLDER_VALIDATION", Map.of("values", List.of("-"))),
                new AuditExample(205, "GROUP_FIELD_COMPLETENESS", pair),
                new AuditExample(206, "RECORD_COMPLETENESS_VALIDATION", Map.of("columnNames", List.of())),
                new AuditExample(207, "CONDITIONAL_REQUIRED_VALIDATION", conditional),
                new AuditExample(306, "NUMERIC_RANGE_VALIDATION", Map.of("minValue", "0")),
                new AuditExample(307, "NUMERIC_RANGE_VALIDATION", Map.of("minValue", "0")),
                new AuditExample(308, "NUMERIC_OUTLIER_VALIDATION", Map.of("minValue", "0")),
                new AuditExample(309, "PLACEHOLDER_VALIDATION", Map.of("values", List.of("-"))),
                new AuditExample(310, "UNIQUE_VALIDATION", Map.of()),
                new AuditExample(311, "COMPOSITE_UNIQUENESS_VALIDATION", pair),
                new AuditExample(403, "CROSS_TABLE_CONSISTENCY", Map.of("objectKey", "11", "referenceObjectKey", "12", "columnNames", List.of("amount", "name"),
                        "leftColumn", "amount", "refColumn", "id", "comparisons", List.of(Map.of("leftColumn", "name", "rightColumn", "name")))),
                new AuditExample(404, "FOREIGN_KEY_VALIDATION", foreign),
                new AuditExample(405, "STATE_CONSISTENCY_VALIDATION", Map.of("objectKey", "11", "conditionColumn", "status", "conditionValues", List.of("ACTIVE"),
                        "assertions", List.of(Map.of("column", "name", "operator", "NOT_NULL")))),
                new AuditExample(504, "TIME_ORDER_VALIDATION", conditions));
    }

    @ParameterizedTest
    @ValueSource(longs = {29, 39})
    void implementedCleanTemplatesCanBeCreatedAndUpdated(long templateId) {
        var template = service.templates("CLEAN").stream().filter(row -> row.getId().equals(templateId)).findFirst().orElseThrow();
        assertTrue(template.isEngineSupported()); assertTrue(template.isEnabled()); assertNull(template.getDisabledReason());
        Map<String, Object> config = templateId == 29
                ? Map.of("stringValue", List.of(Map.of("columns", "updated_at", "type", 0, "sort", 1)))
                : Map.of("dataRange", 1, "dataRangeType", 1, "dataRangeValue", "2026-10-01", "handleType", 1);
        var request = request(templateId, config); request.setKind("CLEAN");
        service.create(request);
        var saved = ArgumentCaptor.forClass(StandardElementRuleDO.class); verify(rules).insert(saved.capture());
        var row = saved.getValue(); row.setId(9L);
        assertEquals(templateId, row.getTemplateId());
        assertTrue(row.getConfigJson().contains(templateId == 29 ? "updated_at" : "2026-10-01"));
        when(rules.selectById(9L)).thenReturn(row); when(rules.selectByOwner(1)).thenReturn(List.of(row));
        request.setId(9L); request.setRevision(0L); service.update(request);
        var updated = ArgumentCaptor.forClass(StandardElementRuleDO.class); verify(rules).updateById(updated.capture());
        assertEquals(1L, updated.getValue().getRevision()); assertEquals(row.getConfigJson(), updated.getValue().getConfigJson());
        verify(cleans, never()).updateById(any(CleanRuleDO.class));
    }

    @Test void unavailableCleanTemplatesAndInvalidConfigRemainRejected() {
        when(cleans.selectList()).thenReturn(List.of(
                CleanRuleDO.builder().id(29L).code("029").name("停用规则").strategyKey("KEEP_LATEST_OR_FIRST").status(1).build(),
                CleanRuleDO.builder().id(30L).code("030").name("未知函数").strategyKey("UNKNOWN").status(0).build(),
                CleanRuleDO.builder().id(31L).code("031").name("未接入规则").status(0).build(),
                CleanRuleDO.builder().id(39L).code("039").name("清理过期记录").strategyKey("CHECK_EXPIRATION").status(0).build()));
        service.templates("CLEAN").stream().filter(row -> row.getId() != 39L).forEach(row -> {
            assertFalse(row.isEnabled()); assertNotNull(row.getDisabledReason());
            var request = request(row.getId(), Map.of()); request.setKind("CLEAN");
            assertThrows(ServiceException.class, () -> service.create(request));
        });
        var request = request(39, Map.of("dataRange", 1, "dataRangeValue", "invalid-date", "handleType", 1)); request.setKind("CLEAN");
        assertThrows(ServiceException.class, () -> service.create(request));
        verify(rules, never()).insert(any(StandardElementRuleDO.class));
    }

    @Test void cleanTemplatesUseRootGroupsAndMalformedParentChainsStayVisible() {
        var root = cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO.builder()
                .id(10L).parentId(0L).categoryName("准确性修正").build();
        var leaf = cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO.builder()
                .id(11L).parentId(10L).categoryName("叶子类").build();
        var cycle = cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO.builder()
                .id(12L).parentId(12L).categoryName("环").build();
        var orphan = cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO.builder()
                .id(13L).parentId(999L).categoryName("缺失父类").build();
        when(cleanCategories.selectList()).thenReturn(List.of(root, leaf, cycle, orphan));
        when(cleans.selectList()).thenReturn(List.of(
                CleanRuleDO.builder().id(1L).code("001").categoryId(11L).build(),
                CleanRuleDO.builder().id(2L).code("002").categoryId(12L).build(),
                CleanRuleDO.builder().id(3L).code("003").categoryId(13L).build(),
                CleanRuleDO.builder().id(4L).code("004").categoryId(999L).build()));
        var templates = service.templates("CLEAN");
        assertEquals(4, templates.size()); assertEquals(10L, templates.getFirst().getCategoryId());
        assertEquals("准确性修正", templates.getFirst().getCategoryName());
        templates.subList(1,4).forEach(row -> { assertNull(row.getCategoryId()); assertEquals("未分类", row.getCategoryName()); });
        verify(cleanCategories).selectList(); verify(cleanCategories, never()).selectById(anyLong());
    }

    @Test void characterSamplesUseJavaRegexAndReturnActualPassFail() {
        var request = new StandardElementRuleSampleReqVO(); request.setElementId(1); request.setTemplateId(107L);
        request.setConfig(Map.of("useRegex", true, "regex", "\\p{Lu}+")); request.setSample("ABC");
        assertTrue(service.sample(request).valid()); request.setSample("abc"); assertFalse(service.sample(request).valid());
        request.setConfig(Map.of("useRegex", true, "regex", "[")); assertThrows(ServiceException.class, () -> service.sample(request));
    }

    @Test void contextRequiresModelPermissionAndTimeFieldsCannotBeCombinedAcrossModels() {
        assertTrue(service.context(1).fields().isEmpty()); verifyNoInteractions(fields);
        when(security.hasPermission("modeling:model:query")).thenReturn(true);
        when(fields.getRuleContextFieldList(1L)).thenReturn(CommonResult.success(List.of(field(11,"start"),field(12,"end"))));
        assertEquals(2, service.context(1).referenceObjects().size());
        var request = request(115, Map.of("conditions", List.of(Map.of("leftField","start","operator","<","rightField","end"))));
        assertThrows(ServiceException.class, () -> service.create(request));
        when(fields.getRuleContextFieldList(1L)).thenReturn(CommonResult.success(List.of(field(11,"start"),field(11,"end"))));
        assertDoesNotThrow(() -> service.create(request));
        request.setConfig(Map.of("conditions", List.of(Map.of("leftField","secret","operator","<","rightField","end"))));
        assertThrows(ServiceException.class, () -> service.create(request));
    }

    @Test void foreignReferenceRequiresAnAuthorizedModelKeyInsteadOfClientPhysicalTable() {
        when(security.hasPermission("modeling:model:query")).thenReturn(true);
        when(fields.getRuleContextFieldList(1L)).thenReturn(CommonResult.success(List.of(field(11,"id"))));
        var request = request(112, Map.of("refTable","secret","refColumn","id"));
        assertThrows(ServiceException.class, () -> service.create(request));
        request.setConfig(Map.of("objectKey","11","columnNames",List.of("id"),"referenceObjectKey","11","refTable","secret","refColumn","id")); service.create(request);
        var captured = ArgumentCaptor.forClass(StandardElementRuleDO.class); verify(rules).insert(captured.capture());
        assertFalse(captured.getValue().getConfigJson().contains("secret"));
        request.setConfig(Map.of("objectKey","11","columnNames",List.of("secret"),"referenceObjectKey","11","refColumn","id"));
        assertThrows(ServiceException.class, () -> service.create(request));
        request.setConfig(Map.of("objectKey","12","columnNames",List.of("id"),"referenceObjectKey","11","refColumn","id"));
        assertThrows(ServiceException.class, () -> service.create(request));
    }

    @Test void updatesRejectStaleRevisionAndWrongAggregate() {
        var prior = StandardElementRuleDO.builder().id(9L).elementId(1).kind("AUDIT").name("规则").templateId(107L).revision(2L).configJson("{\"allowedTypes\":[\"digit\"]}").build();
        when(rules.selectById(9L)).thenReturn(prior); when(rules.selectByOwner(1)).thenReturn(List.of(prior));
        var request = request(107, Map.of("allowedTypes",List.of("digit"))); request.setId(9L); request.setRevision(1L);
        assertThrows(ServiceException.class, () -> service.update(request));
        assertThrows(ServiceException.class, () -> service.get(2,9L));
        verify(rules, never()).updateById(any(StandardElementRuleDO.class));
    }

    @Test void codeMappingsValidateOwnershipDeriveLabelsAndRejectStaleEdits() {
        var mappings = mock(StandardCodeMappingMapper.class); var sets = mock(StandardCodeSetMapper.class);
        var mappingService = new StandardCodeMappingService(mappings,sets,items,catalog);
        when(sets.selectByIdForUpdate(1)).thenReturn(StandardCodeSetDO.builder().id(1).build());
        var item = StandardCodeItemDO.builder().id(7).codeSetId(2).status((short)0).label("真实名称").value("real").build();
        when(items.selectById(7)).thenReturn(item);
        var request = new StandardCodeMappingSaveReqVO(); request.setCodeSetId(1);request.setCodeItemId(7);request.setOriginalValue("source");
        assertThrows(ServiceException.class, () -> mappingService.create(request));
        item.setCodeSetId(1); mappingService.create(request);
        var row = StandardCodeMappingDO.builder().id(8L).codeSetId(1).codeItemId(7).originalValue("source").revision(3L).build();
        when(mappings.selectById(8L)).thenReturn(row);
        assertEquals("真实名称",mappingService.get(1,8L).getCodeName());
        assertThrows(ServiceException.class,()->mappingService.get(2,8L));
        request.setId(8L);request.setRevision(2L);assertThrows(ServiceException.class,()->mappingService.update(request));
    }

    @Test void enumRequiresAnActiveCodeSetAndUsesOnlyEffectiveServerValues() {
        var request = request(111, Map.of("codeSetId", "7", "validValues", List.of("forged")));
        assertThrows(ServiceException.class, () -> service.create(request));
        when(codeSets.selectById(7)).thenReturn(StandardCodeSetDO.builder().id(7).status((short) 1).build());
        assertThrows(ServiceException.class, () -> service.create(request));
        when(codeSets.selectById(7)).thenReturn(StandardCodeSetDO.builder().id(7).status((short) 0).build());
        when(items.selectEnabledByCodeSetId(7)).thenReturn(List.of(StandardCodeItemDO.builder().value("effective").build()));
        service.create(request);
        var saved = ArgumentCaptor.forClass(StandardElementRuleDO.class); verify(rules).insert(saved.capture());
        assertTrue(saved.getValue().getConfigJson().contains("effective"));
        assertFalse(saved.getValue().getConfigJson().contains("forged"));
        assertThrows(ServiceException.class, () -> service.create(request(306, Map.of("minValue", 0.1))));
    }

    private static AuditRuleDO audit(long code,String key) { return AuditRuleDO.builder().id(code).code(Long.toString(code)).name(key).strategyKey(key).status(0).build(); }
    private static StandardElementRuleSaveReqVO request(long template,Map<String,Object> config) {
        var result = new StandardElementRuleSaveReqVO();result.setElementId(1);result.setKind("AUDIT");result.setTemplateId(template);result.setName("规则");result.setStatus(0);result.setConfig(config); return result;
    }
    private static ModelStandardFieldRespDTO field(long model,String name) { var result = new ModelStandardFieldRespDTO();result.setModelId(model);result.setModelName("模型"+model);result.setFieldName(name);return result; }
}
