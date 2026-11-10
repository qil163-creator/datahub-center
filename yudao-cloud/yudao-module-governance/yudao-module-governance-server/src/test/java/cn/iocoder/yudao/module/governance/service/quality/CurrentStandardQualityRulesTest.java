package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompilationService;
import cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CurrentStandardQualityRulesTest {
    private final StandardElementMapper elements = mock(StandardElementMapper.class);
    private final StandardConstraintCompilationService compilation = mock(StandardConstraintCompilationService.class);
    private final QualityRuleMapper rules = mock(QualityRuleMapper.class);
    private final MetaTableMapper tables = mock(MetaTableMapper.class);
    private final MetaDbMapper databases = mock(MetaDbMapper.class);
    private final MetaColumnMapper columns = mock(MetaColumnMapper.class);
    private final StandardQualityRuleMaterializer materializer = new StandardQualityRuleMaterializer(rules);
    private final CurrentStandardQualityRules service = new CurrentStandardQualityRules(
            elements, compilation, materializer, tables, databases, columns, rules);
    private final StandardConstraintCompiler compiler = new StandardConstraintCompiler();

    @Test
    void selectorUsesCurrentConstraintAndKeepsManualRules() {
        var oldElement = element(8);
        var element = element(32);
        var old = rule(oldElement, 10L);
        var manual = QualityRuleDO.builder().id(55L).status(0).build();
        when(elements.selectById(1)).thenReturn(element);
        when(compilation.compile(eq(element))).thenReturn(compile(element));
        var result = service.selectable(List.of(old, manual));
        assertThat(result).hasSize(2);
        assertThat(result.getFirst()).isSameAs(manual);
        assertThat(result.getLast().getId()).isEqualTo(10L);
        assertThat(JsonUtils.parseMap(result.getLast().getParamSchema())).containsEntry("maxLength", 32);
        verifyNoInteractions(rules);
    }

    @Test
    void currentDisabledRuleMustNotBeReplacedByEnabledHistoricalRule() {
        var element = element(32);
        var old = rule(element(8), 10L);
        var disabled = rule(element, 20L);
        disabled.setStatus(1);
        when(elements.selectById(1)).thenReturn(element);
        when(compilation.compile(eq(element))).thenReturn(compile(element));
        assertThat(service.selectable(List.of(old, disabled))).isEmpty();
    }

    @Test
    void assetSelectionRunsCurrentConstraintOnItsExplicitField() {
        var element = element(32);
        var old = rule(element(8), 10L);
        var task = QualityTaskDO.builder().id(1L).taskCode("ASSET-2-col-11").build();
        var object = QualityCheckObjectDO.builder().id(5L).tableId(6L).datasourceConnId(7L)
                .tableName("public.person").build();
        when(tables.selectById(6L)).thenReturn(MetaTableDO.builder().id(6L).dbId(8L)
                .datasourceId(7L).tableName("person").objectStatus(1).build());
        when(databases.selectById(8L)).thenReturn(MetaDbDO.builder().id(8L).datasourceId(7L)
                .dbType("POSTGRESQL").schemaName("public").objectStatus(1).build());
        when(columns.selectById(11L)).thenReturn(MetaColumnDO.builder().id(11L).tableId(6L)
                .datasourceId(7L).columnName("full_name").objectStatus(1).build());
        when(elements.selectByIdForUpdate(1)).thenReturn(element);
        when(compilation.compile(eq(element))).thenReturn(compile(element));
        when(rules.selectListByStandardElementId(1L)).thenReturn(List.of(old));
        var result = service.assetExecution(task, List.of(object), List.of(old));
        assertThat(result).hasSize(1);
        assertThat(JsonUtils.parseMap(result.getFirst().getParamSchema()))
                .containsEntry("column", "full_name").containsEntry("maxLength", 32);
        assertThat(result.getFirst().getExecutionObjectId()).isEqualTo(5L);
        assertThat(JsonUtils.parseMap(old.getParamSchema())).containsEntry("maxLength", 8);
    }

    @Test
    void assetStandardRuleWithoutExplicitFieldFailsClosed() {
        var old = rule(element(8), 10L);
        assertThatThrownBy(() -> service.assetExecution(QualityTaskDO.builder().taskCode("ASSET-2-rule-10-table")
                .build(), List.of(), List.of(old))).hasMessageContaining("明确指定资产字段");
    }

    @Test
    void assetManualRuleKeepsExistingConfigurationWithoutMetadataLookup() {
        var manual = QualityRuleDO.builder().id(42L).status(0).paramSchema("{\"column\":\"x\"}").build();
        assertThat(service.assetExecution(QualityTaskDO.builder().taskCode("ASSET-2-rule-42-table").build(),
                List.of(), List.of(manual))).containsExactly(manual);
        verifyNoInteractions(elements, compilation, rules, tables, databases, columns);
    }

    @Test
    void assetStandardRuleRejectsFieldFromDifferentObject() {
        var old = rule(element(8), 10L);
        when(columns.selectById(11L)).thenReturn(MetaColumnDO.builder().id(11L).tableId(99L)
                .datasourceId(7L).columnName("foreign_name").objectStatus(1).build());
        var object = QualityCheckObjectDO.builder().id(5L).tableId(6L).datasourceConnId(7L).build();
        assertThatThrownBy(() -> service.assetExecution(QualityTaskDO.builder().taskCode("ASSET-2-col-11").build(),
                List.of(object), List.of(old))).hasMessageContaining("与检查对象不匹配");
    }

    @Test
    void selectorHidesDeprecationAndInvalidCurrentDefinition() {
        var element = element(32);
        var old = rule(element(8), 10L);
        element.setLifecycleStatus("DEPRECATED");
        when(elements.selectById(1)).thenReturn(element);
        assertThat(service.selectable(List.of(old))).isEmpty();
        element.setLifecycleStatus("PUBLISHED");
        element.setColumnType(null);
        when(compilation.compile(eq(element))).thenReturn(compile(element));
        assertThat(service.selectable(List.of(old))).isEmpty();
    }

    private StandardElementDO element(int length) {
        return StandardElementDO.builder().id(1).name("姓名").columnType("VARCHAR").nullableFlag(1)
                .dataLength(length).status((short) 0).lifecycleStatus("PUBLISHED").build();
    }

    private StandardConstraintCompiler.Compilation compile(StandardElementDO element) {
        return compiler.compile(element, null, List.of());
    }

    private QualityRuleDO rule(StandardElementDO element, Long id) {
        var rule = materializer.preview(element, compile(element).constraints().getFirst());
        rule.setId(id);
        return rule;
    }
}
