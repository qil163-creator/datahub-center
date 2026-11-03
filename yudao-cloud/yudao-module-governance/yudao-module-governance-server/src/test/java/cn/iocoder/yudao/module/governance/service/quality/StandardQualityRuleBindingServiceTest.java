package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskObjectSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.*;
import cn.iocoder.yudao.module.governance.service.standardconstraint.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StandardQualityRuleBindingServiceTest {
    private final StandardMappingMapper mappings = mock(StandardMappingMapper.class);
    private final MetaColumnMapper columns = mock(MetaColumnMapper.class);
    private final MetaTableMapper tables = mock(MetaTableMapper.class);
    private final MetaDbMapper databases = mock(MetaDbMapper.class);
    private final QualityRuleMapper rules = mock(QualityRuleMapper.class);
    private final StandardElementMapper elements = mock(StandardElementMapper.class);
    private final StandardQualityRuleMaterializer materializer = new StandardQualityRuleMaterializer(rules);
    private final StandardConstraintCompilationService compilation = new StandardConstraintCompilationService(
            new StandardConstraintCompiler(), mock(StandardCodeSetMapper.class), mock(StandardCodeItemMapper.class));
    private final StandardQualityRuleBindingService service = new StandardQualityRuleBindingService(
            mappings, columns, tables, databases, rules, elements, materializer, compilation);
    private final QualityTaskDO task = QualityTaskDO.builder().id(10L).taskCategory("TABLE").build();
    private final LocalDateTime at = LocalDateTime.of(2026, 10, 16, 0, 0);

    @Test
    void newObjectsAndStaleClientIdsMustDiscoverSuggestionsByActualConnectionAndExactRelation() {
        version(1, 8);
        bind(object(1, "person"), 11L, "full_name", 1);
        var database = databases.selectById(70L);
        var table = tables.selectById(1L);
        when(databases.selectListByDatasourceId(7L)).thenReturn(List.of(database));
        when(tables.selectListByDatasourceIdAndName(7L, "person")).thenReturn(List.of(table));
        var resolver = mock(MasterDatabaseResolver.class);
        var route = new MasterDatabaseResolver.ConnectionInfo();
        route.setProductType(ProductTypeEnum.POSTGRESQL); route.setSchema("public");
        when(resolver.resolve(eq(7L), any())).thenReturn(route);
        var evaluationService = new QualityTaskEvaluationService();
        ReflectionTestUtils.setField(evaluationService, "resolver", resolver);
        ReflectionTestUtils.setField(evaluationService, "standardBindings", service);
        var request = new QualityTaskObjectSaveReqVO();
        request.setClientKey("new-object"); request.setDatasourceConnId(7L); request.setTableName("person");

        var suggestions = evaluationService.suggest(List.of(request));
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.getFirst().getObjectKey()).isEqualTo("new-object");
        assertThat(suggestions.getFirst().getStrategyKey()).isEqualTo("LENGTH_VALIDATION");
        assertThat(suggestions.getFirst().getColumnNames()).containsExactly("full_name");
        assertThat(String.valueOf(suggestions.getFirst().getConfig().get("maxLength"))).isEqualTo("8");
        assertThat(request.getTableId()).isNull();

        request.setTableId(999L);
        assertThat(evaluationService.suggest(List.of(request))).hasSize(1);
        route.setSchema("archive");
        assertThat(evaluationService.suggest(List.of(request))).isEmpty();
        request.setTableName("\"public\".\"person\"");
        assertThat(evaluationService.suggest(List.of(request))).hasSize(1);
        request.setTableName("missing");
        assertThat(evaluationService.suggest(List.of(request))).isEmpty();
        route.setSchema(null); request.setTableName("person");
        assertThatThrownBy(() -> evaluationService.suggest(List.of(request)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("精确 schema/table");
        request.setTableName("\"public\".\"person\"");
        assertThat(evaluationService.suggest(List.of(request))).hasSize(1);
        verify(mappings, never()).selectConfirmedMetadataListByTableId(999L);
        verify(tables, never()).selectById(999L);
        verify(rules, never()).insert(any(QualityRuleDO.class));
    }

    @Test
    void suggestionMetadataBindingMustRejectAmbiguityAndIgnoreInactiveOrDifferentScope() {
        var currentDb = MetaDbDO.builder().id(70L).datasourceId(7L).schemaName("public").objectStatus(1).build();
        var otherDb = MetaDbDO.builder().id(71L).datasourceId(7L).schemaName("archive").objectStatus(1).build();
        var inactiveDb = MetaDbDO.builder().id(72L).datasourceId(7L).schemaName("public").objectStatus(2).build();
        var foreignDb = MetaDbDO.builder().id(73L).datasourceId(8L).schemaName("public").objectStatus(1).build();
        when(databases.selectListByDatasourceId(7L)).thenReturn(List.of(currentDb, otherDb, inactiveDb, foreignDb));
        var exact = MetaTableDO.builder().id(1L).dbId(70L).datasourceId(7L).tableName("Person").objectStatus(1).build();
        var other = MetaTableDO.builder().id(2L).dbId(71L).datasourceId(7L).tableName("Person").objectStatus(1).build();
        var inactive = MetaTableDO.builder().id(3L).dbId(70L).datasourceId(7L).tableName("Person").objectStatus(2).build();
        var inactiveParent = MetaTableDO.builder().id(4L).dbId(72L).datasourceId(7L).tableName("Person").objectStatus(1).build();
        var foreign = MetaTableDO.builder().id(5L).dbId(73L).datasourceId(8L).tableName("Person").objectStatus(1).build();
        when(tables.selectListByDatasourceIdAndName(7L, "Person"))
                .thenReturn(List.of(exact, other, inactive, inactiveParent, foreign));
        assertThat(service.findSuggestionTableId(7L, "public", "Person")).isEqualTo(1L);
        assertThat(service.findSuggestionTableId(7L, "archive", "Person")).isEqualTo(2L);
        assertThat(service.findSuggestionTableId(7L, "public", "person")).isNull();
        assertThat(service.findSuggestionTableId(7L, "PUBLIC", "Person")).isNull();
        assertThat(service.findSuggestionTableId(7L, null, "Person")).isNull();
        when(tables.selectListByDatasourceIdAndName(7L, "Person"))
                .thenReturn(List.of(other, inactive, inactiveParent, foreign));
        assertThat(service.findSuggestionTableId(7L, "public", "Person")).isNull();
        var duplicate = MetaTableDO.builder().id(6L).dbId(70L).datasourceId(7L).tableName("Person").objectStatus(1).build();
        when(tables.selectListByDatasourceIdAndName(7L, "Person")).thenReturn(List.of(exact, duplicate));
        assertThatThrownBy(() -> service.findSuggestionTableId(7L, "public", "Person"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("不唯一");
    }

    @Test
    void laterMappingAndLaterTaskShouldUsePublishedTemplateWithoutAnotherPublish() {
        var object = object(1, "person");
        var template = version(1, 8);
        assertThat(service.resolve(task, List.of(object), List.of())).isEmpty();
        bind(object, 11L, "full_name", 1);

        var first = service.resolve(task, List.of(object), List.of());
        var laterTask = QualityTaskDO.builder().id(99L).taskCategory("TABLE").build();
        var later = service.resolve(laterTask, List.of(object), List.of());
        assertThat(first).hasSize(1);
        assertThat(later).hasSize(1);
        assertThat(first.getFirst().getExecutionColumnId()).isEqualTo(11L);
        assertThat(params(first.getFirst())).containsEntry("column", "full_name");
        assertThat(params(template)).doesNotContainKey("column");
        verify(rules, never()).insert(any(QualityRuleDO.class));
    }

    @Test
    void oneTemplateMustBindEachPhysicalColumnAndOnlyItsObject() {
        version(1, 8);
        var a = object(1, "person");
        var b = object(2, "staff");
        bind(a, 11L, "full_name", 1);
        bind(b, 12L, "staff_name", 1);
        var manual = QualityRuleDO.builder().id(55L).paramSchema("{\"column\":\"id\"}").build();
        var bound = service.resolve(task, List.of(a, b), List.of(manual));

        assertThat(bound).hasSize(3);
        assertThat(bound.getFirst()).isSameAs(manual);
        assertThat(bound.subList(1, 3)).extracting(QualityRuleDO::getExecutionObjectId).containsExactly(1L, 2L);
        assertThat(bound.subList(1, 3)).extracting(rule -> params(rule).get("column"))
                .containsExactly("full_name", "staff_name");
    }

    @Test
    void twoColumnsInOneObjectMustKeepTwoBindingsEvenWhenTheyShareOneTemplateId() {
        version(1, 8);
        var object = object(1, "person");
        bind(object, 11L, "full_name", 1);
        when(columns.selectById(12L)).thenReturn(MetaColumnDO.builder().id(12L).tableId(1L)
                .datasourceId(7L).columnName("alias_name").objectStatus(1).build());
        when(mappings.selectConfirmedMetadataListByTableId(1L)).thenReturn(List.of(
                StandardMappingDO.builder().id(111L).elementId(1).columnId(11L).build(),
                StandardMappingDO.builder().id(112L).elementId(1).columnId(12L).build()));
        var bound = service.resolve(task, List.of(object), List.of());
        assertThat(bound).hasSize(2);
        assertThat(bound).extracting(QualityRuleDO::getId).containsOnly(101L);
        assertThat(bound).extracting(QualityRuleDO::getExecutionColumnId).containsExactly(11L, 12L);
        assertThat(bound).extracting(rule -> params(rule).get("column")).containsExactly("full_name", "alias_name");
    }

    @Test
    void currentDefinitionMustApplyToNewPlansWhileFrozenPlanRemainsStable() {
        version(1, 8);
        var object = object(1, "person");
        bind(object, 11L, "full_name", 1);
        var first = service.resolve(task, List.of(object), List.of());
        String frozen = JsonUtils.toJsonString(first.getFirst());
        assertThat(String.valueOf(params(first.getFirst()).get("maxLength"))).isEqualTo("8");
        version(2, 32);
        var next = service.resolve(task, List.of(object), List.of());
        assertThat(String.valueOf(params(next.getFirst()).get("maxLength"))).isEqualTo("32");
        var restored = JsonUtils.parseObject(frozen, QualityRuleDO.class);
        assertThat(restored.getExecutionColumnId()).isEqualTo(11L);
        assertThat(restored.getStandardMappingId()).isEqualTo(111L);
        assertThat(String.valueOf(params(restored).get("maxLength"))).isEqualTo("8");
    }

    @Test
    void assetTaskMustKeepItsExplicitRulesAndNeverDiscoverMetadataMappings() {
        var asset = QualityTaskDO.builder().id(90L).taskCategory("ASSET").build();
        var explicit = QualityRuleDO.builder().id(9L).paramSchema("{\"column\":\"asset_name\"}").build();
        assertThat(service.resolve(asset, List.of(object(1, "person")), List.of(explicit)))
                .containsExactly(explicit);
        verifyNoInteractions(mappings, columns, tables, databases, rules, elements);
    }

    @Test
    void missingOrDeletedColumnMustFailInsteadOfReportingNoViolations() {
        version(1, 8);
        var object = object(1, "person");
        bind(object, 11L, "full_name", 1);
        when(columns.selectById(11L)).thenReturn(null);
        assertThatThrownBy(() -> service.resolve(task, List.of(object), List.of()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("字段已删除");
        when(columns.selectById(11L)).thenReturn(MetaColumnDO.builder().id(11L).tableId(1L)
                .datasourceId(7L).columnName("full_name").objectStatus(2).build());
        assertThatThrownBy(() -> service.resolve(task, List.of(object), List.of()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("字段已删除");
    }

    @Test
    void missingTemplateShouldBePreviewedWithoutWrites() {
        version(1, 8);
        var object = object(1, "person");
        bind(object, 11L, "full_name", 1);
        when(rules.selectListByStandardElementId(1L)).thenReturn(List.of());
        var result = service.resolve(task, List.of(object), List.of());
        assertThat(result).hasSize(1);
        assertThat(String.valueOf(params(result.getFirst()).get("maxLength"))).isEqualTo("8");
        verify(rules, never()).insert(any(QualityRuleDO.class));
    }

    @Test
    void wrongPhysicalTableOrDatasourceMustNotReceiveRules() {
        version(1, 8);
        var object = object(1, "other_table");
        bind(object, 11L, "full_name", 1);
        when(tables.selectById(1L)).thenReturn(MetaTableDO.builder().id(1L).dbId(70L)
                .datasourceId(7L).tableName("person").objectStatus(1).build());
        assertThatThrownBy(() -> service.resolve(task, List.of(object), List.of()))
                .hasMessageContaining("精确定位");
        object.setDatasourceConnId(999L);
        assertThatThrownBy(() -> service.resolve(task, List.of(object), List.of()))
                .hasMessageContaining("数据源或有效状态不一致");
    }

    @Test
    void disabledTemplatesMustNotBeReenabledByExecution() {
        var template = version(1, 8);
        template.setStatus(1);
        var object = object(1, "person");
        bind(object, 11L, "full_name", 1);
        assertThat(service.resolve(task, List.of(object), List.of())).isEmpty();
        verify(rules, never()).updateById(any(QualityRuleDO.class));
    }

    private QualityRuleDO version(int version, int length) {
        var element = StandardElementDO.builder().id(1).name("名称").columnType("VARCHAR")
                .nullableFlag(1).dataLength(length).status((short) 0).lifecycleStatus("PUBLISHED").build();
        when(elements.selectById(1)).thenReturn(element);
        when(elements.selectByIdForUpdate(1)).thenReturn(element);
        var compiled = compilation.compile(element, at);
        var constraint = compiled.constraints().getFirst();
        var template = QualityRuleDO.builder().id(100L + version).standardElementId(1L)
                .status(0).constraintHash(constraint.constraintHash())
                .strategyKey(constraint.strategyKey()).checkLevel("FIELD")
                .paramSchema(JsonUtils.toJsonString(constraint.config())).build();
        when(rules.selectListByStandardElementId(1L)).thenReturn(List.of(template));
        return template;
    }

    private void bind(QualityCheckObjectDO object, Long columnId, String physicalName, int version) {
        when(tables.selectById(object.getTableId())).thenReturn(MetaTableDO.builder().id(object.getTableId())
                .dbId(70L).datasourceId(7L).tableName(object.getTableName()).objectStatus(1).build());
        when(databases.selectById(70L)).thenReturn(MetaDbDO.builder().id(70L).datasourceId(7L)
                .dbType("POSTGRESQL").schemaName("public").objectStatus(1).build());
        when(columns.selectById(columnId)).thenReturn(MetaColumnDO.builder().id(columnId).tableId(object.getTableId())
                .datasourceId(7L).columnName(physicalName).objectStatus(1).build());
        when(mappings.selectConfirmedMetadataListByTableId(object.getTableId())).thenReturn(List.of(
                StandardMappingDO.builder().id(100L + columnId).elementId(1)
                        .columnId(columnId).mappingStatus("CONFIRMED").targetType("METADATA_COLUMN").build()));
    }

    private static QualityCheckObjectDO object(long tableId, String tableName) {
        return QualityCheckObjectDO.builder().id(tableId).tableId(tableId).datasourceConnId(7L).tableName(tableName).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(QualityRuleDO rule) {
        return JsonUtils.parseObject(rule.getParamSchema(), Map.class);
    }
}
