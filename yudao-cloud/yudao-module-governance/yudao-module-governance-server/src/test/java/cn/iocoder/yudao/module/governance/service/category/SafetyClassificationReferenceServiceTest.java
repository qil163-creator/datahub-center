package cn.iocoder.yudao.module.governance.service.category;

import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydata.CategoryDataDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class SafetyClassificationReferenceServiceTest extends BaseMockitoUnitTest {
    @Mock private CategoryDataMapper directoryMapper;
    @Mock private SafetyCategoryMapper categoryMapper;
    @Mock private SafetyLevelMapper levelMapper;
    @Mock private SafetyRuleMapper ruleMapper;
    @InjectMocks private SafetyClassificationReferenceService service;

    @BeforeEach
    void prepare() {
        when(directoryMapper.selectList()).thenReturn(List.of(directory(1L, 0L, (short) 0), directory(2L, 1L, (short) 0),
                directory(3L, 0L, (short) 0)));
        when(categoryMapper.selectList()).thenReturn(List.of(category()));
        when(levelMapper.selectList()).thenReturn(List.of(SafetyLevelDO.builder().id(2L).safetyLevel("L2")
                .color("#1890ff").status((short) 0).build()));
        when(ruleMapper.selectList()).thenReturn(List.of());
    }

    @Test
    void projectsOnlyReferencedDirectoryPathsAndKeepsKindsDistinct() {
        var nodes = service.list();
        assertThat(nodes).extracting("id").containsExactly("dir:1", "dir:2", "category:1");
        var leaf = service.get(1L);
        assertThat(leaf.getCategoryId()).isEqualTo(1L);
        assertThat(leaf.getParentId()).isEqualTo("dir:2");
        assertThat(leaf.getSelectable()).isTrue();
        assertThat(leaf.getStatus()).isZero();
        assertThat(leaf.getLevelName()).isEqualTo("L2");
        assertThat(leaf.getRuleName()).isNull();
    }

    @Test
    void disabledAncestorOrMissingLevelPreventsSelectingClassification() {
        when(directoryMapper.selectList()).thenReturn(List.of(directory(1L, 0L, (short) 1), directory(2L, 1L, (short) 0)));
        assertThat(service.get(1L).getStatus()).isEqualTo((short) 1);
        when(directoryMapper.selectList()).thenReturn(List.of(directory(1L, 0L, (short) 0), directory(2L, 1L, (short) 0)));
        when(levelMapper.selectList()).thenReturn(List.of());
        assertThat(service.get(1L).getStatus()).isEqualTo((short) 1);
    }

    @Test
    void malformedDirectoryCycleAndMissingAncestorFailClosed() {
        when(directoryMapper.selectList()).thenReturn(List.of(directory(1L, 2L, (short) 0), directory(2L, 1L, (short) 0)));
        assertThat(service.get(1L).getStatus()).isEqualTo((short) 1);
        when(directoryMapper.selectList()).thenReturn(List.of(directory(2L, 99L, (short) 0)));
        assertThat(service.get(1L).getStatus()).isEqualTo((short) 1);
    }

    @Test
    void showsOnlyRealLinkedRuleAndUnknownReferenceIsAbsent() {
        var category = category(); category.setRuleId(9L);
        when(categoryMapper.selectList()).thenReturn(List.of(category));
        when(ruleMapper.selectList()).thenReturn(List.of(SafetyRuleDO.builder().id(9L).ruleName("真实本地规则").build()));
        assertThat(service.get(1L).getRuleName()).isEqualTo("真实本地规则");
        assertThat(service.get(999L)).isNull();
        assertThat(service.get(null)).isNull();
    }

    private static CategoryDataDO directory(Long id, Long parentId, Short status) {
        return CategoryDataDO.builder().id(id).parentId(parentId).categoryName("目录" + id).sort(id.intValue()).status(status).build();
    }

    private static SafetyCategoryDO category() {
        return SafetyCategoryDO.builder().id(1L).categoryId(2L).name("测站编码").dataLevelId(2).status((short) 0).sort(0).build();
    }
}
