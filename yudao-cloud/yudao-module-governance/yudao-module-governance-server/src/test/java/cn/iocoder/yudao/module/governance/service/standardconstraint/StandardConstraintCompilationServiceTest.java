package cn.iocoder.yudao.module.governance.service.standardconstraint;

import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StandardConstraintCompilationServiceTest {

    @Test
    void currentCodeValuesAreUsedWhenCompilingAgain() {
        var codeSets = mock(StandardCodeSetMapper.class);
        var codeItems = mock(StandardCodeItemMapper.class);
        var service = new StandardConstraintCompilationService(new StandardConstraintCompiler(), codeSets, codeItems);
        var at = LocalDateTime.of(2026, 9, 11, 12, 0);
        var element = StandardElementDO.builder().id(1).columnType("VARCHAR").nullableFlag(1).codeSetId(9).build();
        when(codeSets.selectById(9)).thenReturn(StandardCodeSetDO.builder().id(9).status((short) 0).build());
        when(codeItems.selectEnabledByCodeSetId(9)).thenReturn(
                List.of(StandardCodeItemDO.builder().id(1).codeSetId(9).value("A").status((short) 0).build()),
                List.of(StandardCodeItemDO.builder().id(2).codeSetId(9).value("B").status((short) 0).build()));

        var first = service.compile(element);
        var second = service.compile(element);

        assertThat(first.compilable()).isTrue();
        assertThat(second.compilable()).isTrue();
        assertThat(first.constraints()).extracting(StandardConstraintCompiler.CompiledConstraint::constraintHash)
                .isNotEqualTo(second.constraints().stream()
                        .map(StandardConstraintCompiler.CompiledConstraint::constraintHash).toList());
    }
}
