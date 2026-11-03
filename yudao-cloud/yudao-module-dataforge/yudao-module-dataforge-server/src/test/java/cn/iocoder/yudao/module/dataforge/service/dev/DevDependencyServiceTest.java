package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevDependencyServiceTest {
    private final DevDependencyService service = new DevDependencyService();
    private final DevRecordMapper records = mock(DevRecordMapper.class);
    private final LocalDate date = LocalDate.of(2026, 10, 1);
    private DevRecordDO downstream;
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "records", records);
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(DevTaskDO.builder().id(1L).build());
        snapshot.getDependencyVersions().put(20L, 90L);
        downstream = DevRecordDO.builder().businessDate(date).snapshotJson(JsonUtils.toJsonString(snapshot)).build();
    }
    @Test void missingDependencyReportsRequiredVersionAndBusinessDate() {
        var item = service.inspect(downstream).getFirst();
        assertFalse(item.getSatisfied()); assertNull(item.getRecordId());
        assertEquals(90L, item.getRequiredVersionId()); assertEquals(date, item.getBusinessDate());
        assertTrue(item.getReason().contains("尚无"));
    }
    @Test void newerVersionSuccessCannotSatisfyFrozenDependency() {
        when(records.selectLatestDependency(20L, null, date)).thenReturn(
                DevRecordDO.builder().id(3L).versionId(91L).status("SUCCESS").qualityStatus("PASSED").build());
        var item = service.inspect(downstream).getFirst();
        assertFalse(item.getSatisfied()); assertEquals(3L, item.getRecordId());
        assertTrue(item.getReason().contains("版本不匹配"));
    }
    @Test void successfulExecutionWithUnknownQualityRemainsBlocked() {
        when(records.selectLatestDependency(20L, 90L, date)).thenReturn(
                DevRecordDO.builder().id(4L).versionId(90L).status("SUCCESS").qualityStatus("UNKNOWN").build());
        var item = service.inspect(downstream).getFirst();
        assertFalse(item.getSatisfied()); assertTrue(item.getReason().contains("质量"));
    }
    @Test void acceptedOlderRunIsDisplayedEvenIfLaterRunFailed() {
        when(records.selectAcceptedDependency(20L, 90L, date)).thenReturn(
                DevRecordDO.builder().id(5L).versionId(90L).status("SUCCESS").qualityStatus("PASSED").build());
        assertTrue(service.inspect(downstream).getFirst().getSatisfied());
        verify(records, never()).selectLatestDependency(20L, 90L, date);
    }
}
