package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.PlanningDomainStatisticsMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningDomainPresentationServiceTest {
    @Test
    void independentPermissionsHideModelCountsWithoutQueryingTheirStore() {
        var service = new PlanningDomainPresentationService();
        var users = mock(AdminUserApi.class);
        var permissions = mock(SecurityFrameworkService.class);
        var counts = mock(PlanningDomainStatisticsMapper.class);
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        ReflectionTestUtils.setField(service, "securityFrameworkService", permissions);
        ReflectionTestUtils.setField(service, "statisticsMapper", counts);
        var row = DataDomainDO.builder().id(1L).leaderUserId(2L).build();
        row.setCreator("system");
        var leader = new AdminUserRespDTO(); leader.setId(2L); leader.setNickname("测试负责人");
        when(users.getUserMap(any())).thenReturn(Map.of(2L, leader));
        var result = service.presentBoards(List.of(row)).getFirst();
        assertEquals("测试负责人", result.getLeaderName());
        assertNull(result.getCreatorName());
        assertNull(result.getBusinessObjectCount());
        assertNull(result.getBusinessActivityCount());
        assertNull(result.getSubjectCount());
        verifyNoInteractions(counts);
    }

    @Test
    void showsRealObjectActivityAndSubjectCounts() {
        var service = new PlanningDomainPresentationService();
        var users = mock(AdminUserApi.class);
        var permissions = mock(SecurityFrameworkService.class);
        var counts = mock(PlanningDomainStatisticsMapper.class);
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        ReflectionTestUtils.setField(service, "securityFrameworkService", permissions);
        ReflectionTestUtils.setField(service, "statisticsMapper", counts);
        when(permissions.hasPermission(anyString())).thenReturn(true);
        var subjectCount = new PlanningDomainStatisticsMapper.CountRow(); subjectCount.setId(1L); subjectCount.setCount(4L);
        var entityCount = new PlanningDomainStatisticsMapper.CountRow(); entityCount.setId(1L); entityCount.setCount(10L); entityCount.setActivityCount(9L);
        when(counts.selectSubjectCounts()).thenReturn(List.of(subjectCount));
        when(counts.selectBoardEntityCounts()).thenReturn(List.of(entityCount));
        var result = service.presentBoards(List.of(DataDomainDO.builder().id(1L).build())).getFirst();
        assertEquals(4L, result.getSubjectCount());
        assertEquals(10L, result.getBusinessObjectCount());
        assertEquals(9L, result.getBusinessActivityCount());
        verifyNoInteractions(users);
    }
}
