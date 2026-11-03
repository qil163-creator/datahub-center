package cn.iocoder.yudao.module.governance.service.category;

import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategoryRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydata.CategoryDataDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SafetyCategoryDisplayTest {
    @Mock CategoryDataMapper directories;
    @Mock SafetyLevelMapper levels;
    @Mock SafetyRuleMapper rules;
    @Mock AdminUserApi users;
    @InjectMocks SafetyCategoryDisplayService service;

    @Test void disabledRuleIsStillConfiguredAndSourceFactsNeverReplaceAuditIdentity() {
        when(directories.selectList()).thenReturn(List.of(CategoryDataDO.builder().id(2L).categoryName("真实类目").build()));
        when(levels.selectList()).thenReturn(List.of(SafetyLevelDO.builder().id(3L).safetyLevel("L3").safetyName("敏感级").color("#123456").build()));
        when(rules.selectList()).thenReturn(List.of(SafetyRuleDO.builder().id(4L).ruleName("停用但已配置").status((short) 1).build()));
        var row = new SafetyCategoryRespVO(); row.setId(9L); row.setCategoryId(2L); row.setDataLevelId(3); row.setRuleId(4L);
        row.setCreator("migration:0226"); row.setUpdater("migration:0226");
        var source = SafetyCategoryDO.builder().id(9L).sourceCreatorName("来源创建者").sourceUpdaterName("来源修改者")
                .sourceCreateTime(LocalDateTime.of(2026, 7, 29, 19, 35)).sourceUpdateTime(LocalDateTime.of(2026, 7, 30, 10, 0)).build();
        source.setCreator("migration:0226");
        service.fill(List.of(row)); service.sourceDisplay(row, source);
        assertThat(row.getConfigured()).isTrue(); assertThat(row.getRuleName()).isEqualTo("停用但已配置");
        assertThat(row.getLevelName()).isEqualTo("L3"); assertThat(row.getLevelDisplayName()).isEqualTo("敏感级");
        assertThat(row.getCreatorName()).isEqualTo("来源创建者"); assertThat(row.getUpdaterName()).isEqualTo("来源修改者");
        assertThat(row.getCreator()).isEqualTo("migration:0226"); assertThat(source.getCreator()).isEqualTo("migration:0226");
        assertThat(row.getCreateTime()).isEqualTo(source.getSourceCreateTime());
        verifyNoInteractions(users);
    }

    @Test void localEditShowsLocalUpdaterAndOptionsRetainFivePrioritiesAndActualGradeSensitiveValue() {
        when(directories.selectList()).thenReturn(List.of(CategoryDataDO.builder().id(9007199254740993L).revision(2L).sort(0).build()));
        when(levels.selectList()).thenReturn(List.of(SafetyLevelDO.builder().id(1L).safetyLevel("L1").safetyName("公开级").sensitiveLevel(10).sort(0).build()));
        when(rules.selectList()).thenReturn(List.of());
        var actor = new AdminUserRespDTO(); actor.setId(9L); actor.setNickname("本地操作人"); when(users.getUserMap(Set.of(9L))).thenReturn(Map.of(9L, actor));
        var row = new SafetyCategoryRespVO(); row.setUpdater("9"); row.setCreator("migration:0226");
        var now = LocalDateTime.now(); row.setUpdateTime(now);
        var source = SafetyCategoryDO.builder().sourceCreatorName("原创建者").build();
        service.fill(List.of(row)); service.sourceDisplay(row, source);
        assertThat(row.getUpdaterName()).isEqualTo("本地操作人"); assertThat(row.getUpdateTime()).isEqualTo(now);
        var options = service.options();
        assertThat(options.getPriorities()).extracting("value").containsExactly("LOWEST", "LOW", "MEDIUM", "HIGH", "HIGHEST");
        assertThat(options.getLevels().getFirst().getSensitiveLevel()).isEqualTo(10);
        assertThat(options.getDirectories().getFirst().getId()).isEqualTo(9007199254740993L);
        assertThat(options.getDirectories().getFirst().getRevision()).isEqualTo(2L);
    }
}
