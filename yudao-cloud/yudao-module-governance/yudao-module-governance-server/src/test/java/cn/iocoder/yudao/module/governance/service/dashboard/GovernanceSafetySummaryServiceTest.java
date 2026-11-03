package cn.iocoder.yudao.module.governance.service.dashboard;

import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.controller.admin.dashboard.GovernanceDashboardController;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.accesslog.SafetyAccessLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyFieldTagMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyIdentifyRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskLogMapper;
import cn.iocoder.yudao.module.governance.service.whitelist.SafetyWhitelistService;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GovernanceSafetySummaryServiceTest extends BaseMockitoUnitTest {
    @Mock private MetaColumnMapper columns;
    @Mock private SafetyFieldTagMapper tags;
    @Mock private SafetyIdentifyRuleMapper rules;
    @Mock private SafetyAccessLogMapper logs;
    @Mock private SafetyLevelMapper levels;
    @Mock private SafetyStaticTaskLogMapper tasks;
    @Mock private SafetyWhitelistService whitelist;
    @InjectMocks private GovernanceSafetySummaryServiceImpl service;

    @Test
    void preservesOverviewDistributionAndAccessTrend() {
        SafetyLevelDO level = new SafetyLevelDO();
        level.setId(2L);
        level.setSafetyName("内部");
        level.setColor("blue");
        when(levels.selectList()).thenReturn(List.of(level));
        when(tags.selectConfirmedGroupByLevel()).thenReturn(List.of(Map.of("levelId", 2L, "cnt", 3L)));
        when(logs.selectTrendSince(any())).thenReturn(List.of(Map.of("day", "2026-10-27", "total", 5L, "exempt", 1L)));
        when(logs.selectCountSince(any())).thenReturn(5L);
        when(logs.selectExemptCountSince(any())).thenReturn(1L);
        when(whitelist.countActiveWhitelist()).thenReturn(2L);
        var result = service.getSummary();
        assertEquals(5L, result.getAccessCount30d());
        assertEquals(1L, result.getExemptCount30d());
        assertEquals(2L, result.getActiveWhitelistCount());
        assertEquals("内部", result.getLevelDistribution().getFirst().getLevelName());
        assertEquals(3L, result.getLevelDistribution().getFirst().getCount());
        assertEquals("2026-10-27", result.getAccessTrend().getFirst().getDate());
        assertEquals(5L, result.getAccessTrend().getFirst().getTotal());
        assertNull(result.getStaticRunSuccessRate());
    }

    @Test
    void summaryEndpointRetainsFineGrainedAuthorization() throws Exception {
        var method = GovernanceDashboardController.class.getMethod("getSafetySummary");
        assertArrayEquals(new String[]{"/safety-summary"}, method.getAnnotation(GetMapping.class).value());
        assertEquals("@ss.hasPermission('governance:dashboard:safety-query')",
                method.getAnnotation(PreAuthorize.class).value());
    }
}
