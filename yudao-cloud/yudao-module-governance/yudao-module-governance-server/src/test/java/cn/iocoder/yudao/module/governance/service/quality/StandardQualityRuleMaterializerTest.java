package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompiler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StandardQualityRuleMaterializerTest {
    private final QualityRuleMapper rules = mock(QualityRuleMapper.class);
    private final StandardQualityRuleMaterializer materializer = new StandardQualityRuleMaterializer(rules);
    private final StandardConstraintCompiler compiler = new StandardConstraintCompiler();

    @Test
    void publishShouldCreateTemplatesWithoutPhysicalColumns() {
        var element = element(8);
        var compiled = compile(element);
        materializer.materialize(element, compiled);
        var inserted = ArgumentCaptor.forClass(QualityRuleDO.class);
        verify(rules, times(compiled.constraints().size())).insert(inserted.capture());
        assertThat(inserted.getAllValues()).allSatisfy(rule -> {
            assertThat(rule.getRuleCode()).startsWith("STD-1-");
            assertThat(rule.getConstraintHash()).hasSize(64);
            assertThat(rule.getExecutionColumnId()).isNull();
            assertThat(rule.getParamSchema()).doesNotContain("column");
        });
    }

    @Test
    void sameConstraintReplayShouldNotChangeExistingTemplatesOrManualStatus() {
        var element = element(8);
        var compiled = compile(element);
        var old = QualityRuleDO.builder().id(9L).standardElementId(1L)
                .constraintHash(compiled.constraints().getFirst().constraintHash()).status(1).build();
        when(rules.selectListByStandardElementId(1L)).thenReturn(List.of(old));
        materializer.materialize(element, compiled);
        materializer.materialize(element, compiled);
        verify(rules, never()).insert(any(QualityRuleDO.class));
        verify(rules, never()).updateById(any(QualityRuleDO.class));
    }

    @Test
    void newConstraintShouldCreateTemplate() {
        var element = element(8);
        materializer.materialize(element, compile(element));
        var inserted = ArgumentCaptor.forClass(QualityRuleDO.class);
        verify(rules).insert(inserted.capture());
        assertThat(inserted.getValue().getRuleCode()).startsWith("STD-1-LENGTH-");
        verify(rules, never()).updateById(any(QualityRuleDO.class));
    }

    @Test
    void changedConstraintsMustNotDisableExistingRules() {
        var next = element(32);
        materializer.materialize(next, compile(next));
        verify(rules).insert(any(QualityRuleDO.class));
        verify(rules, never()).updateById(any(QualityRuleDO.class));
    }

    @Test
    void longElementNameMustKeepConstraintSuffixAndAvoidAmbiguousRuleLookup() {
        var element = element(8);
        element.setName("长".repeat(128));
        String first = StandardQualityRuleMaterializer.buildRuleName(element, compile(element).constraints().getFirst());
        String second = StandardQualityRuleMaterializer.buildRuleName(element, compile(element(32)).constraints().getFirst());
        assertThat(first).hasSizeLessThanOrEqualTo(128).contains("-长度-");
        assertThat(second).hasSizeLessThanOrEqualTo(128).contains("-长度-");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void deprecationShouldDisableGeneratedRules() {
        when(rules.selectListByStandardElementId(1L)).thenReturn(List.of(
                QualityRuleDO.builder().id(1L).status(0).build(),
                QualityRuleDO.builder().id(2L).status(1).build()));
        materializer.disableByElement(1);
        var updated = ArgumentCaptor.forClass(QualityRuleDO.class);
        verify(rules).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(1L);
        assertThat(updated.getValue().getStatus()).isEqualTo(1);
    }

    private StandardConstraintCompiler.Compilation compile(StandardElementDO element) {
        return compiler.compile(element, null, List.of());
    }

    private static StandardElementDO element(int length) {
        return StandardElementDO.builder().id(1).code("DE000001").name("名称")
                .engName("name").columnType("VARCHAR").nullableFlag(1).dataLength(length).build();
    }
}
