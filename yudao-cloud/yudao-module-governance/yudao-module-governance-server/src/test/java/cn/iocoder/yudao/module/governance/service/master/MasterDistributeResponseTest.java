package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class MasterDistributeResponseTest extends BaseMockitoUnitTest {

    @Mock private MasterDistributeMapper tasks;
    @Mock private MasterModelMapper models;
    @Mock private cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeLogMapper logs;
    @Mock private MasterDistributeRunCoordinator coordinator;
    @Mock private AdminUserApi users;
    @InjectMocks private MasterDistributeServiceImpl service;

    @Test
    void pageKeepsScheduleDescriptionAndLatestPublicationWithLosslessWireTypes() {
        LocalDateTime releasedAt = LocalDateTime.of(2026, 10, 12, 9, 0);
        MasterDistributeDO task = task(9007199254740993L);
        task.setTaskType(2);
        task.setStatus(1);
        task.setDescription("每日派发");
        task.setReleaseUserId(9007199254740995L);
        task.setReleaseTime(releasedAt);
        task.setNextRunTime(releasedAt.plusDays(1));
        MasterDistributePageReqVO request = new MasterDistributePageReqVO();
        when(tasks.selectPage(request)).thenReturn(new PageResult<>(List.of(task), 1L));
        model();
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(task.getReleaseUserId());
        user.setNickname("发布负责人");
        when(users.getUserMap(Set.of(task.getReleaseUserId()))).thenReturn(Map.of(user.getId(), user));
        when(coordinator.blockingState(task.getId())).thenReturn(2);

        var page = service.getDistributePage(request);
        var response = page.getList().getFirst();
        assertEquals(1L, page.getTotal());
        assertEquals("测试模型", response.getMasterModelName());
        assertEquals("md_rows", response.getMasterModelTableName());
        assertEquals("每日派发", response.getDescription());
        assertEquals(releasedAt, response.getReleaseTime());
        assertEquals(releasedAt.plusDays(1), response.getNextRunTime());
        assertEquals(task.getReleaseUserId(), response.getReleaseUserId());
        assertEquals("发布负责人", response.getReleaseUserName());
        assertEquals(1, response.getStatus());
        assertEquals(2, response.getBlockingRunStatus());
        assertEquals("http://invalid.test", response.getConfig().getApi().getUrl());

        var module = new tools.jackson.databind.module.SimpleModule();
        module.addSerializer(Long.class, cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer.INSTANCE);
        module.addSerializer(LocalDateTime.class, cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer.INSTANCE);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().addModule(module).build();
        var wire = mapper.readTree(mapper.writeValueAsString(response));
        assertEquals("9007199254740993", wire.get("id").asString());
        assertEquals("9007199254740995", wire.get("releaseUserId").asString());
        assertTrue(wire.get("releaseTime").isIntegralNumber());
        assertTrue(wire.get("nextRunTime").isIntegralNumber());
        assertEquals("每日派发", wire.get("description").asString());
    }

    @Test
    void unpublishedUnknownDetailKeepsLatestPublisherEvenWhenUserNoLongerExists() {
        MasterDistributeDO task = task(10L);
        task.setReleaseUserId(99L);
        task.setReleaseTime(LocalDateTime.of(2026, 10, 12, 9, 0));
        when(tasks.selectById(10L)).thenReturn(task);
        model();
        when(users.getUserMap(Set.of(99L))).thenReturn(Map.of());
        when(coordinator.blockingState(10L)).thenReturn(5);

        var response = service.getDistribute(10L);
        assertEquals(0, response.getStatus());
        assertEquals(5, response.getBlockingRunStatus());
        assertEquals(99L, response.getReleaseUserId());
        assertEquals(task.getReleaseTime(), response.getReleaseTime());
        assertNull(response.getReleaseUserName());
        assertNull(response.getNextRunTime());
    }

    @Test
    void oneOffDraftDoesNotInventSchedulePublicationOrDescription() {
        MasterDistributeDO task = task(10L);
        when(tasks.selectById(10L)).thenReturn(task);
        model();

        var response = service.getDistribute(10L);
        assertEquals(0, response.getStatus());
        assertEquals(0, response.getBlockingRunStatus());
        assertNull(response.getNextRunTime());
        assertNull(response.getReleaseTime());
        assertNull(response.getReleaseUserId());
        assertNull(response.getReleaseUserName());
        assertNull(response.getDescription());
        verifyNoInteractions(users);
    }

    private MasterDistributeDO task(Long id) {
        return MasterDistributeDO.builder().id(id).masterModelId(20L).name("测试派发")
                .taskType(1).status(0).distributeType(2)
                .distributeJson("{\"api\":{\"url\":\"http://invalid.test\"}}")
                .build();
    }

    private void model() {
        MasterModelDO model = new MasterModelDO();
        model.setId(20L);
        model.setTableName("md_rows");
        model.setTableCn("测试模型");
        when(models.selectByIds(anyCollection())).thenReturn(List.of(model));
    }
}
